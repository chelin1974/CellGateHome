package io.github.chelin1974.cellgatehome.homekit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.jmdns.JmDNS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notices when the bridge has stopped being discoverable over mDNS.
 *
 * <p>This exists because of a specific, observed failure. HomeKit discovery and resolution
 * are mDNS multicast to {@code 224.0.0.251:5353}, and when the host loses its route to that
 * group — a network change, an interface going down, a VPN taking the default route, macOS
 * denying local-network access — JmDNS logs a flurry of
 * {@code NoRouteToHostException: No route to host}, tries to recover, and eventually gives
 * up with {@code Could not recover we are Down!}. From that moment the bridge is invisible
 * to HomeKit and every paired accessory reads "No Response", while the process carries on
 * serving HTTP and reporting itself perfectly healthy. JmDNS does not come back on its own.
 *
 * <p>Two independent signals are watched, because neither alone is enough:
 *
 * <ol>
 *   <li><b>JmDNS's own verdict.</b> {@link JmDNS#setDelegate} registers for
 *       {@code cannotRecoverFromIOError}, which is exactly the callback behind that
 *       "we are Down!" log line. Definitive, so it is acted on immediately.
 *   <li><b>Whether multicast still works at all.</b> A periodic send of a real mDNS query
 *       to the group. This catches the broken network before JmDNS has finished giving up,
 *       and it catches the case where JmDNS's recovery thread dies without ever reaching
 *       its delegate. Transient blips happen (a DHCP renew will do it), so this one has to
 *       fail {@link #PROBE_FAILURES_BEFORE_DEAD} times in a row before it counts.
 * </ol>
 *
 * <p>Recovery is deliberately not attempted in process. Rebuilding a JmDNS instance and
 * re-advertising underneath a running HAP server is a great deal of moving machinery for a
 * fault whose honest answer is "restart me": instead this reports the failure, the health
 * endpoint turns DOWN, and whatever supervises the process — a Kubernetes liveness probe,
 * {@code Restart=on-failure} under systemd — restarts it cleanly.
 *
 * <p>The state machine here holds no JmDNS reference and opens no sockets of its own until
 * asked, which keeps it testable without a network.
 */
public final class MdnsWatchdog {

  private static final Logger log = LoggerFactory.getLogger(MdnsWatchdog.class);

  /** The service type HomeKit bridges advertise; the probe asks for it by name. */
  public static final String HAP_SERVICE_TYPE = "_hap._tcp.local.";

  private static final String MDNS_GROUP = "224.0.0.251";
  private static final int MDNS_PORT = 5353;

  /** How often to send the probe. Cheap, but there is no reason to be busy about it. */
  public static final Duration DEFAULT_PROBE_INTERVAL = Duration.ofSeconds(60);

  /**
   * Consecutive probe failures before the advertisement is declared dead.
   *
   * <p>One failure is not news: an interface renewing a DHCP lease will produce it, and
   * restarting over that would be worse than ignoring it. Three in a row, at the default
   * interval, means multicast has been unusable for a couple of minutes.
   */
  public static final int PROBE_FAILURES_BEFORE_DEAD = 3;

  /** How the watchdog tests that multicast still works. Swapped out in tests. */
  public interface Probe {
    void send() throws IOException;
  }

  private final Probe probe;
  private final Consumer<String> onDead;
  private final int failuresBeforeDead;

  private final AtomicReference<String> deadReason = new AtomicReference<>();
  private final AtomicInteger consecutiveProbeFailures = new AtomicInteger();

  private volatile ScheduledFuture<?> scheduled;

  /** Watches the interface the bridge advertises on. */
  public MdnsWatchdog(InetAddress address, Consumer<String> onDead) {
    this(multicastProbe(address), onDead, PROBE_FAILURES_BEFORE_DEAD);
  }

  MdnsWatchdog(Probe probe, Consumer<String> onDead, int failuresBeforeDead) {
    this.probe = probe;
    this.onDead = onDead == null ? reason -> {} : onDead;
    this.failuresBeforeDead = Math.max(1, failuresBeforeDead);
  }

  /**
   * Creates the JmDNS instance the bridge will advertise through, with this watchdog
   * already listening to it.
   *
   * <p>The bridge has to own this instance rather than let HAP-Java create its own: a
   * delegate can only be installed on an instance you hold. Pass the result to
   * {@code new HomekitServer(jmdns, port)}, which advertises through it.
   */
  public JmDNS createJmDns(InetAddress address) throws IOException {
    JmDNS jmdns = JmDNS.create(address);
    attachTo(jmdns);
    return jmdns;
  }

  /** Registers for JmDNS's "I have given up" callback. */
  public void attachTo(JmDNS jmdns) {
    jmdns.setDelegate(
        (dns, services) ->
            markDead(
                "JmDNS gave up recovering its mDNS responder on "
                    + safeHostName(dns)
                    + ". HomeKit can no longer discover or resolve this bridge."));
  }

  /** Starts the periodic multicast probe. */
  public void schedule(ScheduledExecutorService scheduler, Duration interval) {
    long millis = Math.max(1_000, interval.toMillis());
    scheduled =
        scheduler.scheduleWithFixedDelay(
            this::runProbe, millis, millis, TimeUnit.MILLISECONDS);
  }

  public void stop() {
    ScheduledFuture<?> task = scheduled;
    if (task != null) {
      task.cancel(false);
    }
  }

  /** Why the bridge is no longer discoverable, or empty while it still is. */
  public Optional<String> deadReason() {
    return Optional.ofNullable(deadReason.get());
  }

  public boolean isHealthy() {
    return deadReason.get() == null;
  }

  /** Visible for tests; the scheduler calls this. */
  void runProbe() {
    if (!isHealthy()) {
      // Already declared dead. JmDNS does not recover, so there is nothing left to learn
      // and no reason to keep putting packets on the network.
      return;
    }

    try {
      probe.send();
      int failures = consecutiveProbeFailures.getAndSet(0);
      if (failures > 0) {
        log.info("mDNS multicast is working again after {} failed probe(s)", failures);
      }
    } catch (IOException | RuntimeException e) {
      int failures = consecutiveProbeFailures.incrementAndGet();
      String reason = describe(e);

      if (failures >= failuresBeforeDead) {
        markDead(
            "cannot send mDNS multicast to "
                + MDNS_GROUP
                + " after "
                + failures
                + " attempts ("
                + reason
                + "). HomeKit cannot discover or resolve this bridge.");
      } else {
        log.warn(
            "mDNS multicast probe failed ({} of {} before the advertisement is declared"
                + " dead): {}",
            failures,
            failuresBeforeDead,
            reason);
      }
    }
  }

  /** First caller wins; the reason is logged once and the listener runs once. */
  private void markDead(String reason) {
    if (!deadReason.compareAndSet(null, reason)) {
      return;
    }

    log.error("HomeKit advertisement is dead: {}", reason);
    log.error(
        "The bridge is still serving, but nothing in Apple Home can reach it. It needs a"
            + " restart; a supervised deployment will do that on its own.");

    try {
      onDead.accept(reason);
    } catch (RuntimeException e) {
      log.warn("the advertisement-failure listener itself failed: {}", describe(e));
    }
  }

  /**
   * Sends a real mDNS PTR query for {@code _hap._tcp.local.} from the bridge's own address.
   *
   * <p>A well-formed query rather than a scratch packet, so this is indistinguishable from
   * any other Bonjour client on the network and cannot confuse another responder. The reply
   * is not read: the only thing being tested is whether the host can still put a packet on
   * the multicast group, which is precisely what fails when JmDNS starts throwing
   * NoRouteToHostException.
   */
  static Probe multicastProbe(InetAddress address) {
    byte[] query = ptrQuery(HAP_SERVICE_TYPE);

    return () -> {
      InetAddress group = InetAddress.getByName(MDNS_GROUP);
      // Bound to the same address JmDNS uses, so the kernel's route lookup for the group
      // is the same one JmDNS gets - otherwise the probe could pass while JmDNS fails.
      try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(address, 0))) {
        socket.send(new DatagramPacket(query, query.length, group, MDNS_PORT));
      }
    };
  }

  /** A DNS query packet: one question, PTR, asking for a unicast reply. */
  static byte[] ptrQuery(String name) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // Header: id 0, no flags, one question, no answer/authority/additional records.
    out.write(new byte[] {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0}, 0, 12);

    for (String label : name.split("\\.")) {
      if (label.isEmpty()) {
        continue;
      }
      byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
      out.write(bytes.length);
      out.write(bytes, 0, bytes.length);
    }
    out.write(0); // end of name

    out.write(0);
    out.write(12); // QTYPE = PTR

    // QCLASS = IN, with the top bit set: the mDNS "unicast response" flag. Responders
    // answer this socket directly instead of multicasting a reply at the whole network,
    // so a probe every minute costs the LAN almost nothing.
    out.write(0x80);
    out.write(0x01);

    return out.toByteArray();
  }

  private static String safeHostName(JmDNS dns) {
    try {
      return dns == null ? "this host" : dns.getHostName();
    } catch (RuntimeException e) {
      return "this host";
    }
  }

  /**
   * A usable description of a failure. Several of the exceptions thrown here carry a null
   * message, and "cannot send mDNS multicast: null" tells nobody anything.
   */
  private static String describe(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        return error.getClass().getSimpleName() + ": " + current.getMessage();
      }
    }
    return error.getClass().getSimpleName();
  }
}

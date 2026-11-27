package io.github.chelin1974.cellgatehome;

import io.github.chelin1974.cellgatehome.cellgate.CellGateClient;
import io.github.chelin1974.cellgatehome.cellgate.Watchman;
import io.github.chelin1974.cellgatehome.homekit.AccessoryIds;
import io.github.chelin1974.cellgatehome.homekit.FileAuthInfo;
import io.github.chelin1974.cellgatehome.homekit.MdnsWatchdog;
import io.github.chelin1974.cellgatehome.homekit.DoorbellWatcher;
import io.github.chelin1974.cellgatehome.homekit.GateActivityWatcher;
import io.github.chelin1974.cellgatehome.homekit.GateAccessory;
import io.github.chelin1974.cellgatehome.homekit.GateDoorbell;
import io.github.chelin1974.cellgatehome.homekit.HomeKitNames;
import io.github.chelin1974.cellgatehome.homekit.LayoutVersion;
import io.github.chelin1974.cellgatehome.homekit.NetworkAddresses;
import io.github.chelin1974.cellgatehome.metrics.CellGateMetrics;
import io.github.chelin1974.cellgatehome.metrics.OtlpExporter;
import io.github.chelin1974.cellgatehome.web.DashboardModel;
import io.github.chelin1974.cellgatehome.web.DashboardModel.Gate;
import io.github.chelin1974.cellgatehome.web.DashboardServer;
import io.github.hapjava.accessories.HomekitAccessory;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.LogEntry;
import io.github.hapjava.server.HomekitAccessoryCategories;
import io.github.hapjava.server.impl.HomekitRoot;
import io.github.hapjava.server.impl.HomekitServer;
import javax.jmdns.JmDNS;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes each CellGate gate to Apple Home and serves the Gate Board dashboard.
 *
 * <p>On start it signs in to CellGate, reads the account's units, and publishes a gate accessory
 * for each gate. A timer re-reads the gate state from CellGate on every poll and feeds it to the
 * accessories, so Home shows the real open/closed state. The dashboard and the OTLP metrics
 * export run alongside, each on its own thread, so neither can hold up HomeKit.
 */
public final class CellGateHomeBridge implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(CellGateHomeBridge.class);
  private static final int FIRST_ACCESSORY_ID = 2;
  /** How many recent log entries to scan for doorbell rings and gate openings on each poll. */
  private static final int ACTIVITY_COUNT = 20;

  private final CellGateConfig config;
  private final CellGateClient client;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(r -> {
        final var t = new Thread(r, "cellgate-home-poll");
        t.setDaemon(true);
        return t;
      });
  private final CountDownLatch terminated = new CountDownLatch(1);

  private final List<GateHandle> gateHandles = new ArrayList<>();
  private List<Watchman> units = List.of();

  private HomekitServer server;
  private HomekitRoot bridge;
  private DashboardServer dashboard;
  private volatile DashboardModel model;
  private boolean dashboardServedElsewhere;
  private MdnsWatchdog mdnsWatchdog;
  private JmDNS jmdns;
  private volatile Consumer<String> advertisementFailureListener = reason -> {};
  // volatile: pushMetricsSoon() reads this from a HomeKit or dashboard thread, while start()
  // assigns it on another.
  private volatile OtlpExporter exporter;
  private DoorbellWatcher doorbellWatcher;
  private GateActivityWatcher gateActivityWatcher;
  private volatile boolean running;

  /** How often the post-open watch reads the portal; see {@link #watchAfterOpen()}. */
  private static final Duration WATCH_POLL_INTERVAL = Duration.ofSeconds(5);

  private volatile Instant watchUntil = Instant.EPOCH;
  private final java.util.concurrent.atomic.AtomicBoolean watching =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.Set<Long> sensorsThatHaveReported =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** A gate accessory tied back to the unit and port it drives, so polling can update it. */
  private record GateHandle(
      GateAccessory accessory, String name, long unitId, long gatePortId, Long statusPortId) {}

  public CellGateHomeBridge(CellGateConfig config) {
    this(config, new CellGateClient(config.email(), config.password(), config.requestTimeout()));
  }

  CellGateHomeBridge(CellGateConfig config, CellGateClient client) {
    this.config = config;
    this.client = client;
  }

  /**
   * Says where every file the bridge writes is going, once, at startup.
   *
   * <p>Three files land somewhere decided by a data directory, an optional override and a
   * default - enough moving parts that "where is my pairing state?" should be answerable by
   * reading the log, and in a container, by checking these are on the mounted volume rather
   * than the writable layer.
   */
  private void logWrittenPaths() {
    log.info("writing to {}", config.dataDirectory().toAbsolutePath());
    log.info("  pairing state: {}", config.storagePath().toAbsolutePath());
    log.info("  accessory IDs: {}", accessoryIdsFile());
    log.info("  layout number: {}", layoutFile());
  }

  public synchronized void start() throws IOException {
    logWrittenPaths();
    if (running) {
      return;
    }
    client.signIn();
    units = client.devices();
    final List<GateEntry> gates = discoverGates(units);
    if (gates.isEmpty()) {
      log.warn("the CellGate account has no gates this bridge can control; nothing to publish.");
      return;
    }

    final var authInfo = FileAuthInfo.loadOrCreate(config.storagePath());
    final InetAddress address = NetworkAddresses.resolve(config.bindAddress());
    if (address.isLoopbackAddress()) {
      log.warn("advertising HomeKit on {}, a loopback address your iPhone cannot reach."
          + " Set \"bindAddress\" to this machine's LAN IP.", address.getHostAddress());
    }
    // The bridge creates the JmDNS instance itself rather than letting HAP-Java do it: a
    // delegate - the callback that fires when JmDNS gives up recovering - can only be
    // installed on an instance we hold. HomekitServer(JmDNS, port) then advertises through
    // it and binds HTTP to jmdns.getInetAddress(), which is this same address.
    mdnsWatchdog = new MdnsWatchdog(address, this::onAdvertisementDead);
    jmdns = mdnsWatchdog.createJmDns(address);
    server = new HomekitServer(jmdns, config.port());
    bridge = server.createBridge(authInfo, config.bridgeName(), HomekitAccessoryCategories.BRIDGES,
        "CellGate", "CellGate HomeKit Bridge", "cellgate-home-bridge", "1.0.0", "1.0.0");

    final var ids = AccessoryIds.load(accessoryIdsFile());
    final var accessories = new ArrayList<HomekitAccessory>();
    final var dashboardGates = new ArrayList<DashboardModel.Gate>();
    final var bells = new ArrayList<DoorbellWatcher.Bell>();
    final var takenNames = new ArrayList<String>();

    for (final var entry : gates) {
      final String display = HomeKitNames.unique(HomeKitNames.clean(entry.name(), "Gate"), takenNames);
      takenNames.add(display);
      final var accessory = new GateAccessory(
          ids.idFor("gate:" + entry.unit().id() + ":" + entry.gate().id()),
          display,
          entry.unit().serial(),
          entry.unit().hardwareModel(),
          () -> {
            client.triggerGate(entry.unit(), entry.gate());
            pushMetricsSoon();
            watchAfterOpen();
          },
          scheduler,
          config.assumeOpen());
      accessories.add(accessory);
      gateHandles.add(new GateHandle(accessory, display, entry.unit().id(), entry.gate().id(),
          entry.statusPortId()));
      dashboardGates.add(new Gate("gate-" + entry.gate().id(), display, entry.unit().location(), accessory));

      if (config.doorbell()) {
        final String bellName = HomeKitNames.unique(display + " Doorbell", takenNames);
        takenNames.add(bellName);
        final var doorbell = new GateDoorbell(
            ids.idFor("doorbell:" + entry.unit().id() + ":" + entry.gate().id()),
            bellName,
            entry.unit().serial() == null ? null : entry.unit().serial() + "-doorbell",
            entry.unit().hardwareModel(),
            scheduler);
        accessories.add(doorbell);
        bells.add(new DoorbellWatcher.Bell(entry.unit().location(), doorbell));
      }
    }
    if (config.doorbell() && !bells.isEmpty()) {
      doorbellWatcher = new DoorbellWatcher(bells, config.doorbellRingEvents());
    }
    if (!config.assumeOpen().isZero()) {
      final var byPort = new HashMap<Long, GateAccessory>();
      gateHandles.forEach(h -> byPort.put(h.gatePortId(), h.accessory()));
      gateActivityWatcher = new GateActivityWatcher(byPort, config.assumeOpen());
    }

    accessories.forEach(bridge::addAccessory);
    ids.save();
    bridge.setConfigurationIndex(LayoutVersion.resolve(layoutFile(), accessories));

    bridge.start();
    running = true;
    log.info("HomeKit bridge \"{}\" advertised at {}:{} with {} accessory/ies",
        config.bridgeName(), address.getHostAddress(), config.port(), accessories.size());
    if (doorbellWatcher != null) {
      log.info("doorbell on: Home will notify when the gate logs {}", config.doorbellRingEvents());
    }
    if (gateActivityWatcher != null) {
      log.info("openings from the keypad, app and schedules count as open for {}s; holds until released",
          config.assumeOpen().toSeconds());
    }

    if (!authInfo.hasUser()) {
      log.info("");
      log.info("  +------------------------------------------+");
      log.info("  |  Add the bridge in the Home app using     |");
      log.info("  |  setup code:  {}                 |", authInfo.getPin());
      log.info("  +------------------------------------------+");
      log.info("");
    } else {
      log.info("already paired with Apple Home, so no setup code is shown. If the bridge is not in"
          + " the Home app, stop it, delete {}, and start it again.", config.storagePath().toAbsolutePath());
    }

    model = new DashboardModel(dashboardGates, units, client);
    poll(); // first reading before anyone looks
    mdnsWatchdog.schedule(scheduler, MdnsWatchdog.DEFAULT_PROBE_INTERVAL);

    scheduler.scheduleWithFixedDelay(this::poll,
        config.pollInterval().toSeconds(), config.pollInterval().toSeconds(), TimeUnit.SECONDS);
    log.info("reading the gate state every {}s", config.pollInterval().toSeconds());

    startDashboardAndMetrics(address, model);
  }

  private void startDashboardAndMetrics(InetAddress address, DashboardModel model) {
    if (config.otlpEndpoint() != null) {
      exporter = new OtlpExporter(config.otlpEndpoint(),
          () -> CellGateMetrics.collect(model), config.metricsInterval(), "1.0.0");
      exporter.start();
    } else {
      log.info("metrics export is off; set \"prometheusUrl\" to record history");
    }
    if (dashboardServedElsewhere || config.dashboardPort() <= 0) {
      return;
    }
    try {
      dashboard = new DashboardServer(config.dashboardPort(), model, config.dashboardPasscode(),
          () -> advertisementFailure().orElse(null));
      dashboard.start();
      log.info("Gate Board dashboard at http://{}:{}/{}", address.getHostAddress(), dashboard.port(),
          dashboard.switchingEnabled() ? "" : " (view-only: set dashboardPasscode to allow opening)");
    } catch (IOException e) {
      log.warn("could not start the dashboard on port {}: {}. Set dashboardPort to a free port, or 0 to turn it off.",
          config.dashboardPort(), e.getMessage());
    }
  }

  /** Re-reads the units from CellGate and updates each gate's open/closed state. */
  private void poll() {
    try {
      units = client.devices();
    } catch (RuntimeException e) {
      log.warn("could not read the gate state from CellGate: {}", e.getMessage());
      return;
    }
    for (final var h : gateHandles) {
      final Boolean measured = openStateFor(h);
      noteFirstMeasuredReading(h, measured);
      h.accessory().updateOpen(measured);
    }
    readActivity();
  }

  /**
   * Reads the portal every few seconds for a while after a gate is opened.
   *
   * <p>An ordinary poll runs every {@code pollIntervalSeconds}, at least 20, which is fine for
   * a gate sitting still and useless across an opening: the gate can swing open and shut again
   * between two reads. This closes that gap where it matters, for {@code
   * watchAfterOpenSeconds} from the trigger.
   *
   * <p>It also decides a question this bridge cannot otherwise answer. A Watchman whose status
   * input has nothing wired to it never reports a position, and the only moment it would, if
   * it ever did, is around an opening - which an ordinary poll is likely to miss entirely. If
   * a reading appears during the watch, {@link #noteFirstMeasuredReading} says so once, and
   * the measured value takes over from the assumed one everywhere from that point.
   *
   * <p>Re-triggering extends the window rather than starting a second watcher, so holding the
   * button does not multiply the requests to CellGate.
   */
  private void watchAfterOpen() {
    final Duration window = config.watchAfterOpen();
    if (window.isZero()) {
      return;
    }
    watchUntil = Instant.now().plus(window);
    if (watching.compareAndSet(false, true)) {
      scheduleWatchPoll();
    }
  }

  private void scheduleWatchPoll() {
    scheduler.schedule(
        () -> {
          try {
            poll();
          } catch (RuntimeException e) {
            log.debug("watch poll failed: {}", Failures.describe(e));
          } finally {
            if (running && Instant.now().isBefore(watchUntil)) {
              scheduleWatchPoll();
            } else {
              watching.set(false);
            }
          }
        },
        WATCH_POLL_INTERVAL.toSeconds(),
        TimeUnit.SECONDS);
  }

  /**
   * Says once, per gate, that its status sensor has reported a position.
   *
   * <p>Worth a line of its own: until it happens there is no way to tell a unit with nothing
   * wired to its status input from one whose sensor simply has not been caught reporting yet,
   * and the two call for completely different fixes - a contact on the input, or nothing at
   * all. After this, the gate's state is measured and the assumed-open window stops mattering
   * for it.
   */
  private void noteFirstMeasuredReading(GateHandle h, Boolean measured) {
    if (measured == null || !sensorsThatHaveReported.add(h.gatePortId())) {
      return;
    }
    log.info(
        "gate \"{}\" reported a position from its status sensor ({}); its state is measured"
            + " from now on rather than assumed from the last open command.",
        h.name(), measured ? "open" : "closed");
  }

  /**
   * Pushes metrics shortly after a gate is triggered, so the opening cannot fall between two
   * scheduled pushes and vanish.
   *
   * <p>Needed because the two intervals are unrelated: metrics go out every {@code
   * metricsIntervalSeconds} on a fixed cadence, while an assumed-open window lasts {@code
   * assumeOpenSeconds} from whenever you pressed the button. At the defaults - 60 seconds each
   * - an opening lands one sample or none at all, depending where in the cycle it happened to
   * fall. A gate that was opened and recorded nowhere is the whole fault this is meant to fix.
   *
   * <p>Delayed rather than immediate: the caller is {@code GateAccessory}'s trigger, which
   * runs <em>before</em> the accessory marks itself open, so an inline push would collect the
   * state from before the opening. The second push keeps the series from being a single
   * isolated point, which most panels cannot draw.
   */
  private void pushMetricsSoon() {
    final OtlpExporter active = exporter;
    if (active == null) {
      return;
    }
    for (final var seconds : new int[] {2, 15}) {
      scheduler.schedule(active::push, seconds, TimeUnit.SECONDS);
    }
  }

  /**
   * Reads recent activity once and hands it to whoever needs it: the doorbell, for "someone's
   * at the gate" entries, and the gate watcher, for openings the bridge did not send itself.
   */
  private void readActivity() {
    if (doorbellWatcher == null && gateActivityWatcher == null) {
      return;
    }
    final List<LogEntry> entries;
    try {
      entries = client.activity(ACTIVITY_COUNT);
    } catch (RuntimeException e) {
      log.warn("could not read the activity log: {}", e.getMessage());
      return;
    }
    if (doorbellWatcher != null) {
      final int fired = doorbellWatcher.onActivity(entries);
      if (fired > 0) {
        log.debug("rang the doorbell {} time(s) from new gate activity", fired);
      }
    }
    if (gateActivityWatcher != null && gateActivityWatcher.onActivity(entries) > 0) {
      log.info("a gate was opened from outside this bridge; assuming it open");
      pushMetricsSoon();
    }
  }

  /**
   * The gate's open state, read from its status sensor only.
   *
   * <h2>Why the gate port is not a fallback</h2>
   *
   * It is tempting, because on a Watchman EVO the status sensor reports nothing while the
   * gate port carries text that reads like a position:
   *
   * <pre>
   *   port 01  Gate Status     status sensor
   *   port 01  Watchman Gate   GATE (openable) - Gate Closed
   * </pre>
   *
   * That text is not a position. The gate port is an <em>output</em> ({@code portTypeCode}
   * "O", against "I" for the sensor), and opening it is a momentary relay pulse -
   * {@code EVOWTMMomentaryActuationRequest}. Its state is the relay's, not the gate's, so it
   * reads {@code RelayLowStatus} - "Gate Closed" - except during the pulse itself.
   *
   * <p>Falling back to it was tried and reverted: it made every gate report a constant
   * "closed", including while the gate stood open. For a gate that is the worst of the three
   * possible answers. An unknown state is visibly unknown - the dashboard says so and no
   * metric is exported, leaving an empty panel rather than a flat line at zero - whereas a
   * confident "closed" is a false all-clear that looks like real data.
   *
   * <p>So a gate whose sensor reports nothing stays unknown, which is the honest answer:
   * nothing the portal exposes says where that gate is.
   *
   * @return true open, false closed, or null when the sensor reports nothing usable
   */
  private Boolean openStateFor(GateHandle h) {
    for (final var unit : units) {
      if (unit.id() == h.unitId()) {
        return openStateFor(unit, h.statusPortId());
      }
    }
    return null;
  }

  /** The decision on its own, so it can be tested against a unit without running a bridge. */
  static Boolean openStateFor(Watchman unit, Long statusPortId) {
    if (statusPortId == null) {
      return null;
    }
    return unit.port(statusPortId)
        .map(Watchman.Port::statusText)
        .map(CellGateHomeBridge::looksOpen)
        .orElse(null);
  }

  /**
   * CellGate's status text is like "Gate Open" / "Gate Closed" / "Open" / "Closed".
   *
   * <p>"open" is tested first, which decides any text containing both words. That is the
   * safer way round for a gate: reporting open when it is shut is a nuisance, reporting shut
   * when it is standing open is a false all-clear, and HomeKit already reads an unknown
   * state as closed.
   */
  static Boolean looksOpen(String statusText) {
    if (statusText == null) {
      return null;
    }
    final String s = statusText.toLowerCase(java.util.Locale.ROOT);
    if (s.contains("open")) {
      return true;
    }
    if (s.contains("close")) {
      return false;
    }
    return null;
  }

  /** Finds every gate on the account, with the status sensor that reports its position. */
  static List<GateEntry> discoverGates(List<Watchman> units) {
    final var gates = new ArrayList<GateEntry>();
    for (final var unit : units) {
      if (!unit.isEvo()) {
        log.warn("unit \"{}\" is a {}, which this bridge hasn't been checked against; trying anyway.",
            unit.location(), unit.hardwareModel());
      }
      for (final var gate : unit.gates()) {
        final Long statusId = unit.statusSensorFor(gate).map(Watchman.Port::id).orElse(null);
        gates.add(new GateEntry(unit, gate, statusId));
      }
    }
    return gates;
  }

  record GateEntry(Watchman unit, Watchman.Port gate, Long statusPortId) {
    String name() {
      // Prefer the location ("Front gate") over the port's generic "Watchman Gate".
      return unit.location() != null && !unit.location().isBlank() ? unit.location() : gate.name();
    }
  }

  public void awaitTermination() throws InterruptedException {
    terminated.await();
  }

  @Override
  public synchronized void close() {
    if (dashboard != null) {
      dashboard.close();
    }
    if (exporter != null) {
      exporter.close();
    }
    if (mdnsWatchdog != null) {
      mdnsWatchdog.stop();
    }
    scheduler.shutdownNow();
    if (bridge != null) {
      bridge.stop();
    }
    if (server != null) {
      server.stop();
    }
    if (jmdns != null) {
      try {
        // Ours to close, since start() created it.
        jmdns.close();
      } catch (IOException e) {
        log.debug("could not close JmDNS cleanly: {}", e.getMessage());
      }
    }
    running = false;
    terminated.countDown();
  }

  /**
   * Why Apple Home can no longer reach this bridge, or empty while it still can.
   *
   * <p>Separate from whether the bridge is running, on purpose. A bridge whose responder has
   * died is still running - still polling, still serving the dashboard - and still
   * completely invisible to HomeKit. Treating those as one flag is what let the failure go
   * unnoticed.
   */
  public Optional<String> advertisementFailure() {
    return mdnsWatchdog == null ? Optional.empty() : mdnsWatchdog.deadReason();
  }

  /**
   * Called once if the HomeKit advertisement dies.
   *
   * <p>The bridge keeps serving either way, so the dashboard can still show the reason; it
   * is for the caller to decide what to do. Main exits non-zero so the service manager
   * restarts a process that cannot recover on its own.
   */
  /**
   * Stops the bridge starting its own HTTP server for the dashboard.
   *
   * <p>For the Spring Boot flavour, which serves the same page and the same routes through
   * Tomcat. Only one of the two may bind the dashboard port, and Spring is already listening on
   * it by the time the bridge starts, so this is how the bridge stands down. Must be called
   * before {@link #start()}.
   */
  public void dashboardServedElsewhere() {
    this.dashboardServedElsewhere = true;
  }

  /**
   * What the gate dashboard reads, once the units have been discovered.
   *
   * <p>Empty until {@link #start()} has built it. The CLI flavour never needs this - the bridge
   * serves the page itself on {@code dashboardPort} - but the Spring Boot flavour serves the
   * same data through its own controllers, with {@code dashboardPort} set to 0 so only one of
   * the two binds a port.
   */
  public Optional<DashboardModel> model() {
    return Optional.ofNullable(model);
  }

  public void onAdvertisementFailure(Consumer<String> listener) {
    this.advertisementFailureListener = listener == null ? reason -> {} : listener;
  }

  private void onAdvertisementDead(String reason) {
    try {
      advertisementFailureListener.accept(reason);
    } catch (RuntimeException e) {
      log.warn("the advertisement-failure listener failed: {}", e.getMessage());
    }
  }

  private Path accessoryIdsFile() {
    return sibling("cellgate-home.accessories.json");
  }

  private Path layoutFile() {
    return sibling("cellgate-home.layout.json");
  }

  /**
   * A file in the data directory.
   *
   * <p>This used to hang off {@code storagePath}'s parent, which meant pointing the pairing
   * state somewhere else quietly took the accessory IDs and the layout number with it. They
   * follow {@code dataDirectory} now, so the three move together or not at all.
   */
  private Path sibling(String name) {
    return config.dataDirectory().toAbsolutePath().resolve(name);
  }

  /** For the {@code --list} command: sign in and read the account without touching HomeKit. */
  public List<Watchman> listDevices() {
    client.signIn();
    return client.devices();
  }
}

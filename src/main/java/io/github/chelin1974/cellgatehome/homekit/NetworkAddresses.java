package io.github.chelin1974.cellgatehome.homekit;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Picks the address the HomeKit server advertises over Bonjour.
 *
 * <p>HAP-Java's default is {@link InetAddress#getLocalHost()}. On macOS that commonly resolves
 * to 127.0.0.1 or to a VPN, AirDrop or Docker interface, so iOS discovers the bridge at an
 * address it cannot reach and the Home app never finds it. This picks a real LAN address
 * instead: an up, non-loopback, non-virtual interface with a private IPv4 address,
 * preferring the usual primary interfaces ({@code en0} on a Mac, {@code eth0}/{@code wlan0}
 * on Linux).
 */
public final class NetworkAddresses {

  /** Interface name prefixes that are never the LAN the iPhone is on. */
  private static final List<String> IGNORED_PREFIXES =
      List.of(
          "lo", "utun", "awdl", "llw", "bridge", "docker", "br-", "veth", "vmnet", "vboxnet",
          "tun", "tap", "anpi", "ap", "gif", "stf", "zt", "tailscale", "wg");

  private NetworkAddresses() {}

  /** A usable interface and its address, for log lines and tests. */
  public record Candidate(String interfaceName, InetAddress address) {}

  /**
   * The address to bind and advertise.
   *
   * @param configured an explicit {@code bindAddress} from the config, or null to choose
   */
  public static InetAddress resolve(String configured) throws UnknownHostException {
    if (configured != null && !configured.isBlank()) {
      return InetAddress.getByName(configured.trim());
    }
    return choose(candidates()).map(Candidate::address).orElse(InetAddress.getLocalHost());
  }

  /** Every interface address that could plausibly be the home LAN. */
  public static List<Candidate> candidates() {
    List<Candidate> found = new ArrayList<>();
    try {
      for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()) {
          continue;
        }
        String name = nic.getName().toLowerCase(Locale.ROOT);
        if (isIgnored(name)) {
          continue;
        }
        for (InetAddress address : Collections.list(nic.getInetAddresses())) {
          if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
            found.add(new Candidate(nic.getName(), address));
          }
        }
      }
    } catch (SocketException e) {
      // Fall through to the caller's fallback.
    }
    return found;
  }

  /** Pure choice, so the preference order can be tested without real interfaces. */
  public static Optional<Candidate> choose(List<Candidate> candidates) {
    return candidates.stream()
        .filter(c -> !isIgnored(c.interfaceName().toLowerCase(Locale.ROOT)))
        .filter(c -> c.address() instanceof Inet4Address && c.address().isSiteLocalAddress())
        .min(Comparator.comparingInt(c -> rank(c.interfaceName())));
  }

  static boolean isIgnored(String name) {
    return IGNORED_PREFIXES.stream().anyMatch(name::startsWith);
  }

  /** Lower is better: the usual primary interface first, then other wired/Wi-Fi ones. */
  private static int rank(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.equals("en0") || n.equals("eth0") || n.equals("wlan0")) {
      return 0;
    }
    if (n.startsWith("en") || n.startsWith("eth") || n.startsWith("wl")) {
      return 1;
    }
    return 2;
  }
}

package io.github.chelin1974.cellgatehome;

import io.github.chelin1974.cellgatehome.cellgate.CellGateClient;
import io.github.chelin1974.cellgatehome.cellgate.Watchman;
import io.github.chelin1974.cellgatehome.homekit.AccessoryIds;
import io.github.chelin1974.cellgatehome.homekit.FileAuthInfo;
import io.github.chelin1974.cellgatehome.homekit.GateAccessory;
import io.github.chelin1974.cellgatehome.homekit.HomeKitNames;
import io.github.chelin1974.cellgatehome.homekit.LayoutVersion;
import io.github.chelin1974.cellgatehome.homekit.NetworkAddresses;
import io.github.chelin1974.cellgatehome.metrics.CellGateMetrics;
import io.github.chelin1974.cellgatehome.metrics.OtlpExporter;
import io.github.chelin1974.cellgatehome.web.DashboardModel;
import io.github.chelin1974.cellgatehome.web.DashboardModel.Gate;
import io.github.chelin1974.cellgatehome.web.DashboardServer;
import io.github.hapjava.accessories.HomekitAccessory;
import io.github.hapjava.server.HomekitAccessoryCategories;
import io.github.hapjava.server.impl.HomekitRoot;
import io.github.hapjava.server.impl.HomekitServer;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

  private final CellGateConfig config;
  private final CellGateClient client;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cellgate-home-poll");
        t.setDaemon(true);
        return t;
      });
  private final CountDownLatch terminated = new CountDownLatch(1);

  private final List<GateHandle> gateHandles = new ArrayList<>();
  private List<Watchman> units = List.of();

  private HomekitServer server;
  private HomekitRoot bridge;
  private DashboardServer dashboard;
  private OtlpExporter exporter;
  private volatile boolean running;

  /** A gate accessory tied back to the unit and port it drives, so polling can update it. */
  private record GateHandle(GateAccessory accessory, long unitId, long gatePortId, Long statusPortId) {}

  public CellGateHomeBridge(CellGateConfig config) {
    this(config, new CellGateClient(config.email(), config.password(), config.requestTimeout()));
  }

  CellGateHomeBridge(CellGateConfig config, CellGateClient client) {
    this.config = config;
    this.client = client;
  }

  public synchronized void start() throws IOException {
    if (running) {
      return;
    }
    client.signIn();
    units = client.devices();
    List<GateEntry> gates = discoverGates(units);
    if (gates.isEmpty()) {
      log.warn("the CellGate account has no gates this bridge can control; nothing to publish.");
      return;
    }

    FileAuthInfo authInfo = FileAuthInfo.loadOrCreate(config.storagePath());
    InetAddress address = NetworkAddresses.resolve(config.bindAddress());
    if (address.isLoopbackAddress()) {
      log.warn("advertising HomeKit on {}, a loopback address your iPhone cannot reach."
          + " Set \"bindAddress\" to this machine's LAN IP.", address.getHostAddress());
    }
    server = new HomekitServer(address, config.port());
    bridge = server.createBridge(authInfo, config.bridgeName(), HomekitAccessoryCategories.BRIDGES,
        "CellGate", "CellGate HomeKit Bridge", "cellgate-home-bridge", "1.0.0", "1.0.0");

    AccessoryIds ids = AccessoryIds.load(accessoryIdsFile());
    List<HomekitAccessory> accessories = new ArrayList<>();
    List<DashboardModel.Gate> dashboardGates = new ArrayList<>();
    List<String> takenNames = new ArrayList<>();

    for (GateEntry entry : gates) {
      String display = HomeKitNames.unique(HomeKitNames.clean(entry.name(), "Gate"), takenNames);
      takenNames.add(display);
      GateAccessory accessory = new GateAccessory(
          ids.idFor("gate:" + entry.unit().id() + ":" + entry.gate().id()),
          display,
          entry.unit().serial(),
          entry.unit().hardwareModel(),
          () -> client.triggerGate(entry.unit(), entry.gate()),
          scheduler);
      accessories.add(accessory);
      gateHandles.add(new GateHandle(accessory, entry.unit().id(), entry.gate().id(),
          entry.statusPortId()));
      dashboardGates.add(new Gate("gate-" + entry.gate().id(), display, entry.unit().location(), accessory));
    }

    accessories.forEach(bridge::addAccessory);
    ids.save();
    bridge.setConfigurationIndex(LayoutVersion.resolve(layoutFile(), accessories));

    bridge.start();
    running = true;
    log.info("HomeKit bridge \"{}\" advertised at {}:{} with {} gate(s)",
        config.bridgeName(), address.getHostAddress(), config.port(), accessories.size());

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

    DashboardModel model = new DashboardModel(dashboardGates, units, client);
    poll(); // first reading before anyone looks
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
    if (config.dashboardPort() <= 0) {
      return;
    }
    try {
      dashboard = new DashboardServer(config.dashboardPort(), model, config.dashboardPasscode());
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
    for (GateHandle h : gateHandles) {
      h.accessory().updateOpen(openStateFor(h));
    }
  }

  /** The gate's open state from its status sensor, or null when the unit reports none. */
  private Boolean openStateFor(GateHandle h) {
    for (Watchman unit : units) {
      if (unit.id() != h.unitId()) {
        continue;
      }
      if (h.statusPortId() == null) {
        return null;
      }
      return unit.port(h.statusPortId())
          .map(Watchman.Port::statusText)
          .map(CellGateHomeBridge::looksOpen)
          .orElse(null);
    }
    return null;
  }

  /** CellGate's status text is like "Gate Open" / "Gate Closed" / "Open" / "Closed". */
  static Boolean looksOpen(String statusText) {
    if (statusText == null) {
      return null;
    }
    String s = statusText.toLowerCase(java.util.Locale.ROOT);
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
    List<GateEntry> gates = new ArrayList<>();
    for (Watchman unit : units) {
      if (!unit.isEvo()) {
        log.warn("unit \"{}\" is a {}, which this bridge hasn't been checked against; trying anyway.",
            unit.location(), unit.hardwareModel());
      }
      for (Watchman.Port gate : unit.gates()) {
        Long statusId = unit.statusSensorFor(gate).map(Watchman.Port::id).orElse(null);
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
    scheduler.shutdownNow();
    if (bridge != null) {
      bridge.stop();
    }
    if (server != null) {
      server.stop();
    }
    running = false;
    terminated.countDown();
  }

  private Path accessoryIdsFile() {
    return sibling("cellgate-home.accessories.json");
  }

  private Path layoutFile() {
    return sibling("cellgate-home.layout.json");
  }

  private Path sibling(String name) {
    Path parent = config.storagePath().toAbsolutePath().getParent();
    return parent == null ? Path.of(name) : parent.resolve(name);
  }

  /** For the {@code --list} command: sign in and read the account without touching HomeKit. */
  public List<Watchman> listDevices() {
    client.signIn();
    return client.devices();
  }
}

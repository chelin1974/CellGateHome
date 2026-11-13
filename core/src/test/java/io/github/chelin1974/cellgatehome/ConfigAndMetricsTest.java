package io.github.chelin1974.cellgatehome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.cellgatehome.CellGateConfig.ConfigException;
import io.github.chelin1974.cellgatehome.homekit.GateAccessory;
import io.github.chelin1974.cellgatehome.metrics.CellGateMetrics;
import io.github.chelin1974.cellgatehome.metrics.CellGateMetrics.Point;
import io.github.chelin1974.cellgatehome.web.DashboardModel;
import io.github.chelin1974.cellgatehome.web.DashboardModel.Gate;
import io.github.chelin1974.cellgatehome.web.DashboardServer;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigAndMetricsTest {

  private static CellGateConfig config(String json) throws Exception {
    Path file = Files.createTempDirectory("cg").resolve("cellgate.config.json");
    Files.writeString(file, json);
    return CellGateConfig.load(file);
  }

  @Test
  @DisplayName("an absolute storagePath is taken as given")
  void absoluteStoragePath() throws Exception {
    CellGateConfig c =
        config(
            "{\"email\":\"a@b.com\",\"password\":\"x\","
                + "\"storagePath\":\"/data/cellgate-home.state.json\"}");

    assertEquals(Path.of("/data/cellgate-home.state.json"), c.storagePath());
  }

  /**
   * The rule shared with the SpanHome and DaikinHome bridges. This used to resolve against the
   * process working directory, so the same config meant different files depending on where the
   * bridge was started from - and losing track of that file un-pairs every controller.
   */
  @Test
  @DisplayName("a relative storagePath is resolved against the config file's directory")
  void relativeStoragePathResolvesAgainstTheConfigDirectory() throws Exception {
    Path directory = Files.createTempDirectory("cg");
    Path file = directory.resolve("cellgate.config.json");
    Files.writeString(
        file, "{\"email\":\"a@b.com\",\"password\":\"x\",\"storagePath\":\"data/state.json\"}");

    CellGateConfig c = CellGateConfig.load(file);

    assertEquals(directory.resolve("data/state.json"), c.storagePath());
  }

  @Test
  @DisplayName("with no storagePath the state file sits beside the config file")
  void defaultStoragePath() throws Exception {
    Path directory = Files.createTempDirectory("cg");
    Path file = directory.resolve("cellgate.config.json");
    Files.writeString(file, "{\"email\":\"a@b.com\",\"password\":\"x\"}");

    CellGateConfig c = CellGateConfig.load(file);

    assertEquals(directory.resolve("cellgate-home.state.json"), c.storagePath());
  }

  @Test
  @DisplayName("email and password are required; prometheusUrl turns on the OTLP endpoint")
  void config() throws Exception {
    assertThrows(ConfigException.class, () -> config("{}"));
    assertThrows(ConfigException.class, () -> config("{\"email\":\"a@b.com\"}"));
    CellGateConfig c = config("{\"email\":\"a@b.com\",\"password\":\"x\",\"prometheusUrl\":\"http://localhost:9090/\"}");
    assertEquals("http://localhost:9090/api/v1/otlp/v1/metrics", c.otlpEndpoint().toString());
    assertEquals(Duration.ofSeconds(60), c.metricsInterval());
    assertNull(config("{\"email\":\"a@b.com\",\"password\":\"x\"}").otlpEndpoint());
    // The poll interval can't go below the floor.
    assertEquals(Duration.ofSeconds(20),
        config("{\"email\":\"a@b.com\",\"password\":\"x\",\"pollIntervalSeconds\":1}").pollInterval());
  }

  @Test
  @DisplayName("metrics send 1 for an open gate, 0 for closed, and nothing for an unknown gate")
  void metrics() {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      GateAccessory open = gate("Front", scheduler);
      open.updateOpen(true);
      GateAccessory closed = gate("Side", scheduler);
      closed.updateOpen(false);
      GateAccessory unknown = gate("Back", scheduler); // never updated

      DashboardModel model = new DashboardModel(List.of(
          new Gate("g1", "Front", "Home", open),
          new Gate("g2", "Side", "Home", closed),
          new Gate("g3", "Back", "Home", unknown)), List.of(), null);

      List<Point> points = CellGateMetrics.collect(model);
      assertEquals(2, points.size(), "the unknown gate is left out");
      Map<String, Double> byGate = new java.util.HashMap<>();
      points.forEach(p -> byGate.put(p.attributes().get("gate"), p.value()));
      assertEquals(1.0, byGate.get("Front"));
      assertEquals(0.0, byGate.get("Side"));
      assertEquals("cellgate.gate.open", points.get(0).name());

      @SuppressWarnings("unchecked")
      Map<String, Object> req = CellGateMetrics.request(points, 1700000000000000000L, Map.of("service.name", "cellgate-home"), "1.0.0");
      var rm = ((List<Map<String, Object>>) req.get("resourceMetrics")).get(0);
      var scope = ((List<Map<String, Object>>) rm.get("scopeMetrics")).get(0);
      var metrics = (List<Map<String, Object>>) scope.get("metrics");
      assertEquals(1, metrics.size(), "both points share one gauge metric");
      var dps = (List<Object>) ((Map<String, Object>) metrics.get(0).get("gauge")).get("dataPoints");
      assertEquals(2, dps.size());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  @DisplayName("the dashboard answers only local addresses and parses image query params")
  void dashboardLocalOnly() throws Exception {
    assertTrue(DashboardServer.isLocal(InetAddress.getByName("192.168.1.5")));
    assertTrue(DashboardServer.isLocal(InetAddress.getByName("127.0.0.1")));
    assertTrue(DashboardServer.isLocal(InetAddress.getByName("10.0.0.2")));
    assertEquals(false, DashboardServer.isLocal(InetAddress.getByName("8.8.8.8")));
    assertEquals("a b/c", DashboardServer.paramValue("name=a%20b%2Fc", "name"));
    assertNull(DashboardServer.paramValue("x=1", "name"));
  }

  private static GateAccessory gate(String name, ScheduledExecutorService scheduler) {
    return new GateAccessory(2, name, "serial", "WTM-EVO", () -> {}, scheduler);
  }

  /**
   * The reason the assumption exists at all. A gate with no position sensor used to export
   * nothing ever - CellGateMetrics skips a gate with no reading - so the Grafana panel stayed
   * empty however often the gate was opened, with no log line to say why.
   */
  @Test
  @DisplayName("opening a gate with no sensor exports a point, so the opening reaches Prometheus")
  void assumedOpenIsExported() {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      GateAccessory sensorless = new GateAccessory(
          2, "Front", "serial", "WTM-EVO", () -> {}, scheduler, java.time.Duration.ofMinutes(1));

      DashboardModel model =
          new DashboardModel(List.of(new Gate("g1", "Front", "Home", sensorless)), List.of(), null);

      assertEquals(0, CellGateMetrics.collect(model).size(), "nothing known yet, nothing sent");

      sensorless.open();

      List<Point> points = CellGateMetrics.collect(model);
      assertEquals(1, points.size(), "the opening is exported");
      assertEquals(1.0, points.get(0).value());
      assertEquals("Front", points.get(0).attributes().get("gate"));
    } finally {
      scheduler.shutdownNow();
    }
  }
}

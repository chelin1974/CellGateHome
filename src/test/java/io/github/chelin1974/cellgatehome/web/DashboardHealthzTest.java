package io.github.chelin1974.cellgatehome.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@code /healthz} endpoint a supervisor's liveness probe depends on.
 *
 * <p>These matter more than they look: if this endpoint answered 200 while the mDNS
 * responder was dead, the probe would pass forever and nothing would ever restart a bridge
 * that Apple Home cannot reach. If it answered 403 to the probe's own address, the pod
 * would restart in a loop instead.
 */
class DashboardHealthzTest {

  /** An empty model; the health endpoint does not read it, and nothing else is called. */
  private static final DashboardModel EMPTY_MODEL =
      new DashboardModel(List.of(), List.of(), null);

  private final AtomicReference<String> failure = new AtomicReference<>();
  private DashboardServer server;
  private int port;

  @BeforeEach
  void startServer() throws IOException {
    server = new DashboardServer(0, EMPTY_MODEL, "passcode", failure::get);
    server.start();
    port = server.port();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  private int statusOf(String path) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(5_000);
    connection.setReadTimeout(5_000);
    try {
      return connection.getResponseCode();
    } finally {
      connection.disconnect();
    }
  }

  private String bodyOf(String path) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
    connection.setConnectTimeout(5_000);
    connection.setReadTimeout(5_000);
    try {
      var stream =
          connection.getResponseCode() >= 400
              ? connection.getErrorStream()
              : connection.getInputStream();
      return stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } finally {
      connection.disconnect();
    }
  }

  @Test
  @DisplayName("answers 200 while the bridge is discoverable")
  void healthyWhileDiscoverable() throws IOException {
    assertEquals(200, statusOf("/healthz"));
    assertTrue(bodyOf("/healthz").contains("\"discoverable\":true"), bodyOf("/healthz"));
  }

  @Test
  @DisplayName("answers 503 with the reason once the advertisement is dead")
  void unhealthyOnceAdvertisementIsDead() throws IOException {
    failure.set("JmDNS gave up recovering its mDNS responder");

    assertEquals(503, statusOf("/healthz"));

    String body = bodyOf("/healthz");
    assertTrue(body.contains("\"discoverable\":false"), body);
    assertTrue(body.contains("JmDNS gave up"), body);
  }

  @Test
  @DisplayName("reflects the live state on every request, not a value cached at startup")
  void reflectsLiveState() throws IOException {
    assertEquals(200, statusOf("/healthz"));
    failure.set("responder died");
    assertEquals(503, statusOf("/healthz"));
  }

  @Test
  @DisplayName("the dashboard page itself still answers, so the reason stays readable")
  void pageStillServedWhenUnhealthy() throws IOException {
    failure.set("responder died");

    // Deliberate: the bridge keeps serving when its advertisement dies, so a human can
    // still open the dashboard and see why. Only /healthz turns red.
    assertEquals(200, statusOf("/"));
  }

  @Test
  @DisplayName("the default constructor reports healthy, for callers that supply no source")
  void defaultConstructorIsHealthy() throws IOException {
    try (DashboardServer plain = new DashboardServer(0, EMPTY_MODEL, null)) {
      plain.start();
      HttpURLConnection connection =
          (HttpURLConnection)
              URI.create("http://127.0.0.1:" + plain.port() + "/healthz").toURL().openConnection();
      try {
        assertEquals(200, connection.getResponseCode());
      } finally {
        connection.disconnect();
      }
    }
  }
}

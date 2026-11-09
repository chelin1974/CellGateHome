package io.github.chelin1974.cellgatehome.web;

import io.github.chelin1974.cellgatehome.CellGateConfig;
import io.github.chelin1974.cellgatehome.CellGateHomeBridge;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gate dashboard and the JSON it reads.
 *
 * <p>Same paths and same response shapes as the bridge's own HTTP server, because the page is
 * the same file and already knows what to expect. The page itself lives in core, on the
 * classpath at {@code /dashboard/index.html}, so both flavours serve one copy.
 *
 * <p>Everything here is a read, so nothing needs the household passcode - including the image
 * routes, which {@code camera-ffmpeg} and Scrypted poll without being able to send headers.
 * They are still LAN-only, through {@link LocalNetworkFilter}.
 */
@RestController
public class DashboardController {

  /** How many activity entries the page shows. */
  static final int ACTIVITY_COUNT = 25;

  private static final Map<String, Object> NOT_READY =
      Map.of(
          "error",
          "The bridge has not finished reading the CellGate units yet. "
              + "This page will work once it has.");

  private final CellGateHomeBridge bridge;
  private final CellGateConfig config;

  public DashboardController(CellGateHomeBridge bridge, CellGateConfig config) {
    this.bridge = bridge;
    this.config = config;
  }

  /** Opening the gate needs the household passcode; with none set the page is view-only. */
  private boolean switchingEnabled() {
    return config.dashboardPasscode() != null && !config.dashboardPasscode().isBlank();
  }

  @GetMapping(path = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<byte[]> page() throws IOException {
    try (InputStream in = DashboardController.class.getResourceAsStream("/dashboard/index.html")) {
      if (in == null) {
        return ResponseEntity.internalServerError()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                "{\"error\":\"The dashboard page is missing from the jar.\"}"
                    .getBytes(StandardCharsets.UTF_8));
      }
      return ResponseEntity.ok()
          .contentType(MediaType.TEXT_HTML)
          .cacheControl(CacheControl.noStore())
          .body(in.readAllBytes());
    }
  }

  @GetMapping(path = "/api/state", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> state() {
    Optional<DashboardModel> model = bridge.model();
    if (model.isEmpty()) {
      return notReady();
    }
    return ResponseEntity.ok(model.get().state(switchingEnabled()));
  }

  @GetMapping(path = "/api/activity", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> activity() {
    Optional<DashboardModel> model = bridge.model();
    if (model.isEmpty()) {
      return notReady();
    }
    return fromCellGate(() -> Map.of("entries", model.get().activity(ACTIVITY_COUNT)));
  }

  @GetMapping(path = "/api/cameras", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> cameras() {
    Optional<DashboardModel> model = bridge.model();
    if (model.isEmpty()) {
      return notReady();
    }
    return fromCellGate(() -> Map.of("cameras", model.get().cameras()));
  }

  /** One stored attachment, by name. Cacheable, because an attachment never changes. */
  @GetMapping(path = "/api/image")
  public ResponseEntity<Object> image(
      @RequestParam(name = "name", required = false) String name) {
    if (name == null || name.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "Pass ?name=<attachment>."));
    }
    Optional<DashboardModel> model = bridge.model();
    if (model.isEmpty()) {
      return notReady();
    }
    return jpeg(
        () -> model.get().image(name),
        "No such image.",
        CacheControl.maxAge(Duration.ofDays(1)));
  }

  /**
   * The latest camera still as a plain JPEG, for Homebridge or Scrypted to poll.
   *
   * <p>Never cached: the whole point of it is that it is current.
   */
  @GetMapping(path = "/snapshot.jpg")
  public ResponseEntity<Object> snapshot(
      @RequestParam(name = "port", required = false) String port) {
    Optional<DashboardModel> model = bridge.model();
    if (model.isEmpty()) {
      return notReady();
    }
    Long portId = parsePort(port);
    return jpeg(
        () -> model.get().latestSnapshot(portId),
        "No snapshot available yet.",
        CacheControl.noStore());
  }

  /** An unreadable port falls through to the first camera, as the bridge's own server did. */
  private static Long parsePort(String port) {
    if (port == null || port.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(port.trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  // ------------------------------------------------------------------ helpers

  private interface Images {
    Optional<byte[]> get();
  }

  private interface Body {
    Map<String, Object> get();
  }

  private static ResponseEntity<Object> jpeg(
      Images images, String missingMessage, CacheControl caching) {
    Optional<byte[]> bytes;
    try {
      bytes = images.get();
    } catch (RuntimeException e) {
      return cellGateDidNotAnswer(e);
    }
    if (bytes.isEmpty()) {
      return ResponseEntity.status(404).body(Map.of("error", missingMessage));
    }
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .cacheControl(caching)
        .body(bytes.get());
  }

  private static ResponseEntity<Object> fromCellGate(Body body) {
    try {
      return ResponseEntity.ok(body.get());
    } catch (RuntimeException e) {
      return cellGateDidNotAnswer(e);
    }
  }

  /** CellGate's cloud failing is a 502: it is upstream, not us. */
  private static ResponseEntity<Object> cellGateDidNotAnswer(RuntimeException e) {
    return ResponseEntity.status(502)
        .body(Map.of("error", "CellGate didn't answer: " + e.getMessage()));
  }

  /**
   * 503 while the units are still being read, rather than an empty page: the service comes up
   * before the bridge has finished, and this is the honest answer in between.
   */
  private static ResponseEntity<Object> notReady() {
    return ResponseEntity.status(503).body(NOT_READY);
  }
}

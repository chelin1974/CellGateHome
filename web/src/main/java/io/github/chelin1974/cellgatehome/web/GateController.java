package io.github.chelin1974.cellgatehome.web;

import io.github.chelin1974.cellgatehome.CellGateConfig;
import io.github.chelin1974.cellgatehome.CellGateHomeBridge;
import io.github.chelin1974.cellgatehome.web.DashboardModel.OpenResult;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two routes that make something happen: opening a gate, and asking the cameras for a
 * picture.
 *
 * <p>Guarded three ways, all carried over from the bridge's own HTTP server unchanged:
 *
 * <ul>
 *   <li>the request must come from the home network, which {@link LocalNetworkFilter} does;
 *   <li>it must carry the household passcode in {@code X-Passcode}, compared in constant time;
 *   <li>five wrong passcodes from one address lock that address out for a while, so the
 *       passcode cannot be guessed at LAN speed.
 * </ul>
 *
 * <p>With no {@code dashboardPasscode} configured, opening the gate is off entirely and the
 * page is view-only. That matters more here than on the other bridges: the thing behind this
 * endpoint is the gate to the property.
 */
@RestController
public class GateController {

  private static final Logger log = LoggerFactory.getLogger(GateController.class);

  static final int MAX_FAILURES = 5;
  static final Duration LOCKOUT = Duration.ofMinutes(5);

  private final CellGateHomeBridge bridge;
  private final byte[] passcode;
  private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

  private static final class Attempts {
    int count;
    Instant lockedUntil = Instant.EPOCH;
  }

  public GateController(CellGateHomeBridge bridge, CellGateConfig config) {
    this.bridge = bridge;
    String configured = config.dashboardPasscode();
    this.passcode =
        configured == null || configured.isBlank()
            ? null
            : configured.getBytes(StandardCharsets.UTF_8);
  }

  @PostMapping(path = "/api/gates/{gateId}/open", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> open(
      @PathVariable String gateId,
      @RequestHeader(name = "X-Passcode", required = false) String given,
      HttpServletRequest request) {

    ResponseEntity<Object> refusal = authorize(given, request.getRemoteAddr());
    if (refusal != null) {
      return refusal;
    }

    Optional<DashboardModel> model = bridge.model();
    if (model.isEmpty()) {
      return notReady();
    }

    OpenResult result = model.get().open(gateId);
    return switch (result) {
      case OK -> {
        log.info("dashboard: opened gate {}", gateId);
        yield ResponseEntity.ok(Map.of("ok", true));
      }
      case NOT_FOUND -> ResponseEntity.status(404).body(Map.of("error", "No such gate."));
      case FAILED ->
          ResponseEntity.status(502)
              .body(Map.of("error", "CellGate did not take the command."));
    };
  }

  @PostMapping(path = "/api/pictures", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> takePictures(
      @RequestHeader(name = "X-Passcode", required = false) String given,
      HttpServletRequest request) {

    ResponseEntity<Object> refusal = authorize(given, request.getRemoteAddr());
    if (refusal != null) {
      return refusal;
    }

    Optional<DashboardModel> model = bridge.model();
    if (model.isEmpty()) {
      return notReady();
    }

    try {
      model.get().takePictures();
      return ResponseEntity.ok(Map.of("ok", true));
    } catch (RuntimeException e) {
      return ResponseEntity.status(502)
          .body(Map.of("error", "CellGate did not take the command: " + e.getMessage()));
    }
  }

  /**
   * Checks opening is on, the address is not locked out, and the passcode matches.
   *
   * @return the response to send when the request is refused, or null when it may proceed
   */
  private ResponseEntity<Object> authorize(String given, String who) {
    if (passcode == null) {
      return ResponseEntity.status(403)
          .body(
              Map.of(
                  "error",
                  "Opening the gate from the dashboard is off. Set dashboardPasscode in "
                      + "cellgate.config.json."));
    }

    Attempts record = attempts.computeIfAbsent(who, key -> new Attempts());
    synchronized (record) {
      if (Instant.now().isBefore(record.lockedUntil)) {
        return ResponseEntity.status(429)
            .body(Map.of("error", "Too many wrong passcodes. Try again in a few minutes."));
      }
      if (given == null
          || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), passcode)) {
        if (++record.count >= MAX_FAILURES) {
          record.lockedUntil = Instant.now().plus(LOCKOUT);
          record.count = 0;
          log.warn(
              "dashboard: {} wrong passcodes from {}; locked out for {} minutes",
              MAX_FAILURES,
              who,
              LOCKOUT.toMinutes());
        }
        return ResponseEntity.status(401).body(Map.of("error", "Wrong passcode."));
      }
      record.count = 0;
    }
    return null;
  }

  private static ResponseEntity<Object> notReady() {
    return ResponseEntity.status(503)
        .body(Map.of("error", "The bridge has not finished reading the CellGate units yet."));
  }
}

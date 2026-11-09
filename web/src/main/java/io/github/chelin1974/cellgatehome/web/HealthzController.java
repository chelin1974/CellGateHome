package io.github.chelin1974.cellgatehome.web;

import io.github.chelin1974.cellgatehome.CellGateHomeBridge;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /healthz}, kept exactly as the bridge's own HTTP server answered it.
 *
 * <p>Actuator's {@code /actuator/health/liveness} says the same thing and is what a new
 * deployment should use. This stays because the existing Kubernetes manifests, the compose
 * healthcheck and anyone's monitoring already point at {@code /healthz}, and silently moving a
 * liveness endpoint during a migration is how a pod ends up never being restarted.
 *
 * <p>503 once the mDNS responder has died. The bridge is still serving this very page at that
 * point, which is the trap: without an endpoint that tells the truth, a liveness probe keeps
 * passing forever while nothing in Apple Home can reach the bridge. JmDNS does not recover, so
 * a restart is the only fix and a failing probe is how the supervisor learns to perform one.
 *
 * <p>Exempt from {@link LocalNetworkFilter}, like the actuator endpoints.
 */
@RestController
public class HealthzController {

  private final CellGateHomeBridge bridge;

  public HealthzController(CellGateHomeBridge bridge) {
    this.bridge = bridge;
  }

  @GetMapping(path = "/healthz", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> healthz() {
    return bridge
        .advertisementFailure()
        .map(
            failure ->
                ResponseEntity.status(503)
                    .body(
                        Map.<String, Object>of(
                            "status", "unhealthy", "discoverable", false, "error", failure)))
        .orElseGet(
            () -> ResponseEntity.ok(Map.<String, Object>of("status", "ok", "discoverable", true)));
  }
}

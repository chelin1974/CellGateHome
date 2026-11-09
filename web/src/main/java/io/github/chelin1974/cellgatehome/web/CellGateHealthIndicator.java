package io.github.chelin1974.cellgatehome.web;

import io.github.chelin1974.cellgatehome.CellGateHomeBridge;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/health}, reporting both ways the bridge can be unwell.
 *
 * <p>DOWN when the bridge is not running, and also DOWN when it is running but HomeKit cannot
 * see it - a bridge whose mDNS responder has died still reads the panels and still serves the
 * dashboard, so "running" on its own is not health.
 *
 * <p>This indicator is deliberately **not** in the liveness or readiness groups. A panel being
 * unreachable is outside this process's control, and a liveness probe on it would restart the
 * container in a loop during a panel outage. See {@code application.yml}, where those groups
 * are limited to the JVM and the HTTP listener.
 */
@Component("cellgateBridge")
public class CellGateHealthIndicator implements HealthIndicator {

  private final CellGateHomeBridge bridge;
  private final BridgeStatus status;

  public CellGateHealthIndicator(CellGateHomeBridge bridge, BridgeStatus status) {
    this.bridge = bridge;
    this.status = status;
  }

  @Override
  public Health health() {
    String advertisementFailure = bridge.advertisementFailure().orElse(null);
    boolean healthy = status.isRunning() && advertisementFailure == null;

    Health.Builder builder = healthy ? Health.up() : Health.down();
    builder.withDetail("running", status.isRunning());
    builder.withDetail("discoverable", advertisementFailure == null);
    status.problem().ifPresent(problem -> builder.withDetail("problem", problem));
    if (advertisementFailure != null) {
      builder.withDetail("advertisementError", advertisementFailure);
    }
    return builder.build();
  }
}

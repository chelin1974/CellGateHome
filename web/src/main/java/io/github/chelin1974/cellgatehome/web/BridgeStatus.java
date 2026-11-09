package io.github.chelin1974.cellgatehome.web;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What the bridge is currently doing, for the health endpoints and the page.
 *
 * <p>Two independent facts, kept apart on purpose. A bridge can be running perfectly - reading
 * panels, serving this page - while being completely invisible to Apple Home because its mDNS
 * responder has died. Collapsing those into one "healthy" flag is exactly what let that
 * failure go unnoticed before there was a watchdog.
 */
@Component
public class BridgeStatus {

  private volatile boolean running;
  private volatile String problem = "the bridge has not finished starting yet";
  private volatile String advertisementFailure;

  public synchronized void running() {
    this.running = true;
    this.problem = null;
  }

  /** Not started, but it will be retried. */
  public synchronized void recoverable(String reason) {
    this.running = false;
    this.problem = reason;
  }

  /** The HomeKit advertisement is dead; only a restart fixes it. */
  public synchronized void advertisementDead(String reason) {
    this.advertisementFailure = reason;
  }

  public boolean isRunning() {
    return running;
  }

  /** Why the bridge is not running, when it is not. */
  public Optional<String> problem() {
    return Optional.ofNullable(problem);
  }

  /** Why HomeKit cannot see the bridge, when it cannot. */
  public Optional<String> advertisementFailure() {
    return Optional.ofNullable(advertisementFailure);
  }

  public boolean discoverable() {
    return advertisementFailure == null;
  }
}

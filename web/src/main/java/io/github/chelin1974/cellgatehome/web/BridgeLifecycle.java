package io.github.chelin1974.cellgatehome.web;

import io.github.chelin1974.cellgatehome.CellGateHomeBridge;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Starts the bridge once the web application is up, and keeps trying if it can't.
 *
 * <p>Deliberately does not fail application startup when the CellGate cloud is unreachable. A service
 * whose health endpoint is unreachable because its dependency is down is a service you cannot
 * diagnose - better to come up, report DOWN, serve the dashboard with the reason on it, and
 * retry in the background.
 */
@Component
public class BridgeLifecycle {

  private static final Logger log = LoggerFactory.getLogger(BridgeLifecycle.class);

  /** Long enough not to hammer an unreachable cloud, short enough to recover unattended. */
  private static final long RETRY_SECONDS = 60;

  private final CellGateHomeBridge bridge;
  private final BridgeStatus status;
  private final ApplicationEventPublisher events;

  private final ScheduledExecutorService retries =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "cellgate-bridge-start");
            thread.setDaemon(true);
            return thread;
          });

  public BridgeLifecycle(
      CellGateHomeBridge bridge, BridgeStatus status, ApplicationEventPublisher events) {
    this.bridge = bridge;
    this.status = status;
    this.events = events;
  }

  /**
   * Off the event thread, on purpose.
   *
   * <p>Starting the bridge means discovering panels over MQTT, which can take the better part
   * of a minute. Doing that inline leaves Spring reporting the application OUT_OF_SERVICE
   * until it returns, so readiness lies for as long as discovery takes.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void startBridge() {
    retries.execute(this::attemptStart);
  }

  /**
   * The mDNS responder dying is not recoverable in-process - JmDNS does not come back - so
   * this publishes BROKEN, which turns {@code /actuator/health/liveness} 503 and gets the pod
   * restarted. Readiness is left alone: the dashboard still serves and is the only place the
   * reason can be read.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void watchAdvertisement() {
    bridge.onAdvertisementFailure(
        reason -> {
          log.error("HomeKit advertisement is dead: {}", reason);
          status.advertisementDead(reason);
          AvailabilityChangeEvent.publish(events, this, LivenessState.BROKEN);
        });
  }

  private void attemptStart() {
    try {
      bridge.start();
      status.running();
      log.info("bridge started");
    } catch (Exception e) {
      String reason = io.github.chelin1974.cellgatehome.Failures.describe(e);
      status.recoverable(reason);
      log.warn("could not start the bridge, retrying in {}s: {}", RETRY_SECONDS, reason);
      retries.schedule(this::attemptStart, RETRY_SECONDS, TimeUnit.SECONDS);
    }
  }

  @PreDestroy
  public void shutdown() {
    retries.shutdownNow();
    bridge.close();
  }
}

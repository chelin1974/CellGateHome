package io.github.chelin1974.cellgatehome.homekit;

import io.github.hapjava.accessories.GarageDoorOpenerAccessory;
import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback;
import io.github.hapjava.characteristics.impl.garagedoor.CurrentDoorStateEnum;
import io.github.hapjava.characteristics.impl.garagedoor.TargetDoorStateEnum;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A CellGate gate, as a HomeKit garage-door accessory (the closest HomeKit type for a gate).
 *
 * <ul>
 *   <li><b>Open/Closed</b> comes from the unit's gate-status input when it has one; while a
 *       trigger is in flight the accessory shows an optimistic "opening" so Home reacts at once.
 *   <li>A gate only <b>opens</b> on command. CellGate decides when it closes (its own timer, or
 *       a close command from the keypad or app), so a "close" from Home is treated as a no-op
 *       and the tile snaps back.
 * </ul>
 *
 * <p>Reads never block: the state is whatever the last poll put here.
 */
public final class GateAccessory implements GarageDoorOpenerAccessory {

  private static final Logger log = LoggerFactory.getLogger(GateAccessory.class);

  /** How long Home shows "opening" before the next poll's real state takes over. */
  static final Duration OPTIMISTIC_WINDOW = Duration.ofSeconds(30);

  /** Opens the gate; the implementation calls CellGate. */
  @FunctionalInterface
  public interface Trigger {
    void open() throws Exception;
  }

  private final int id;
  private final String name;
  private final String serial;
  private final String firmware;
  private final Trigger trigger;
  private final ScheduledExecutorService scheduler;

  private final AtomicReference<HomekitCharacteristicChangeCallback> onCurrent = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onTarget = new AtomicReference<>();

  /** Null until the first reading; true means open. */
  private volatile Boolean open;
  private volatile boolean optimisticOpening;
  private volatile Instant optimisticUntil = Instant.EPOCH;

  public GateAccessory(int id, String name, String serial, String firmware, Trigger trigger,
      ScheduledExecutorService scheduler) {
    this.id = id;
    this.name = name;
    this.serial = serial;
    this.firmware = firmware == null ? "1.0.0" : firmware;
    this.trigger = trigger;
    this.scheduler = scheduler;
  }

  /** Feeds in a freshly polled state. Null means "not reported"; fires HomeKit on a change. */
  public void updateOpen(Boolean nowOpen) {
    Boolean was = open;
    open = nowOpen;
    if (nowOpen != null && optimisticOpening && nowOpen) {
      optimisticOpening = false; // the gate really opened; stop faking it
    }
    if (!java.util.Objects.equals(was, nowOpen)) {
      fire(onCurrent);
      fire(onTarget);
    }
  }

  public boolean hasReading() {
    return open != null;
  }

  public Boolean isOpen() {
    return open;
  }

  // ------------------------------------------------------------- door state

  @Override
  public CompletableFuture<CurrentDoorStateEnum> getCurrentDoorState() {
    if (optimisticOpening && Instant.now().isBefore(optimisticUntil)) {
      return CompletableFuture.completedFuture(CurrentDoorStateEnum.OPENING);
    }
    Boolean o = open;
    // Unknown state reads as closed: HomeKit has no "unknown", and closed is the safe default.
    return CompletableFuture.completedFuture(
        Boolean.TRUE.equals(o) ? CurrentDoorStateEnum.OPEN : CurrentDoorStateEnum.CLOSED);
  }

  @Override
  public CompletableFuture<TargetDoorStateEnum> getTargetDoorState() {
    if (optimisticOpening && Instant.now().isBefore(optimisticUntil)) {
      return CompletableFuture.completedFuture(TargetDoorStateEnum.OPEN);
    }
    return CompletableFuture.completedFuture(
        Boolean.TRUE.equals(open) ? TargetDoorStateEnum.OPEN : TargetDoorStateEnum.CLOSED);
  }

  @Override
  public CompletableFuture<Void> setTargetDoorState(TargetDoorStateEnum state) {
    if (state == TargetDoorStateEnum.CLOSED) {
      // CellGate controls closing, not us. Snap the tile back to where the gate actually is.
      log.info("ignored a request to close \"{}\": the gate closes on its own.", name);
      snapBack();
      return CompletableFuture.completedFuture(null);
    }
    open();
    return CompletableFuture.completedFuture(null);
  }

  /** Opens the gate and shows "opening" at once; used by Home and the dashboard. */
  public void open() {
    log.info("opening gate \"{}\"", name);
    optimisticOpening = true;
    optimisticUntil = Instant.now().plus(OPTIMISTIC_WINDOW);
    fire(onCurrent);
    fire(onTarget);
    try {
      trigger.open();
    } catch (Exception e) {
      log.warn("could not open \"{}\": {}", name, e.getMessage());
      optimisticOpening = false;
      snapBack();
      return;
    }
    // When the window ends, fall back to the real polled state.
    scheduler.schedule(() -> {
      optimisticOpening = false;
      fire(onCurrent);
      fire(onTarget);
    }, OPTIMISTIC_WINDOW.toMillis() + 500, TimeUnit.MILLISECONDS);
  }

  private void snapBack() {
    scheduler.schedule(() -> {
      fire(onCurrent);
      fire(onTarget);
    }, 500, TimeUnit.MILLISECONDS);
  }

  @Override
  public CompletableFuture<Boolean> getObstructionDetected() {
    return CompletableFuture.completedFuture(false);
  }

  @Override
  public void subscribeCurrentDoorState(HomekitCharacteristicChangeCallback callback) {
    onCurrent.set(callback);
  }

  @Override
  public void unsubscribeCurrentDoorState() {
    onCurrent.set(null);
  }

  @Override
  public void subscribeTargetDoorState(HomekitCharacteristicChangeCallback callback) {
    onTarget.set(callback);
  }

  @Override
  public void unsubscribeTargetDoorState() {
    onTarget.set(null);
  }

  @Override
  public void subscribeObstructionDetected(HomekitCharacteristicChangeCallback callback) {
    // Never changes.
  }

  @Override
  public void unsubscribeObstructionDetected() {
    // Never subscribed.
  }

  // --------------------------------------------------------------- identity

  @Override
  public int getId() {
    return id;
  }

  @Override
  public CompletableFuture<String> getName() {
    return CompletableFuture.completedFuture(name);
  }

  @Override
  public void identify() {
    // A gate has nothing to flash.
  }

  @Override
  public CompletableFuture<String> getSerialNumber() {
    return CompletableFuture.completedFuture(serial);
  }

  @Override
  public CompletableFuture<String> getModel() {
    return CompletableFuture.completedFuture("CellGate Watchman");
  }

  @Override
  public CompletableFuture<String> getManufacturer() {
    return CompletableFuture.completedFuture("CellGate");
  }

  @Override
  public CompletableFuture<String> getFirmwareRevision() {
    return CompletableFuture.completedFuture(HomeKitNames.firmware(firmware));
  }

  private static void fire(AtomicReference<HomekitCharacteristicChangeCallback> ref) {
    HomekitCharacteristicChangeCallback cb = ref.get();
    if (cb != null) {
      cb.changed();
    }
  }
}

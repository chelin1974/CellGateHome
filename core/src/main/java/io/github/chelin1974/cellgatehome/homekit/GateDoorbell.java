package io.github.chelin1974.cellgatehome.homekit;

import io.github.hapjava.accessories.DoorbellAccessory;
import io.github.hapjava.accessories.optionalcharacteristic.AccessoryWithName;
import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback;
import io.github.hapjava.characteristics.impl.common.ProgrammableSwitchEnum;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A HomeKit doorbell for a gate: it rings when someone is at the gate, which makes Apple Home push a
 * "Front gate" notification to every phone in the home.
 *
 * <p>CellGate has no real doorbell, so the bridge watches the activity log and calls {@link #ring()}
 * when a new "someone's here" event appears (by default the snapshot the gate camera takes whenever
 * someone uses the keypad). A doorbell is an IP programmable-switch: between rings the switch value
 * is {@code null} (no event), and a ring is a single momentary {@code SINGLE_PRESS} that HomeKit
 * delivers as an event and then clears.
 */
public final class GateDoorbell implements DoorbellAccessory, AccessoryWithName {

  private static final Logger log = LoggerFactory.getLogger(GateDoorbell.class);

  /** How long the SINGLE_PRESS stays readable after a ring, so HomeKit can pick up the event. */
  private static final long PRESS_HOLD_MILLIS = 1000L;

  private final int id;
  private final String name;
  private final String serial;
  private final String firmware;
  private final ScheduledExecutorService scheduler;

  private final AtomicReference<HomekitCharacteristicChangeCallback> onEvent = new AtomicReference<>();
  /** Null except briefly after a ring, as an IP doorbell requires. */
  private final AtomicReference<ProgrammableSwitchEnum> pending = new AtomicReference<>();

  public GateDoorbell(int id, String name, String serial, String firmware,
      ScheduledExecutorService scheduler) {
    this.id = id;
    this.name = name;
    this.serial = serial;
    this.firmware = firmware == null ? "1.0.0" : firmware;
    this.scheduler = scheduler;
  }

  /** Rings the doorbell: HomeKit sends a single-press event and Home shows the gate notification. */
  public void ring() {
    log.info("ringing doorbell \"{}\"", name);
    pending.set(ProgrammableSwitchEnum.SINGLE_PRESS);
    HomekitCharacteristicChangeCallback cb = onEvent.get();
    if (cb != null) {
      cb.changed();
    }
    // Clear the momentary event so later reads return "no event" again.
    scheduler.schedule(() -> pending.set(null), PRESS_HOLD_MILLIS, TimeUnit.MILLISECONDS);
  }

  @Override
  public CompletableFuture<ProgrammableSwitchEnum> getSwitchEvent() {
    return CompletableFuture.completedFuture(pending.get());
  }

  @Override
  public void subscribeSwitchEvent(HomekitCharacteristicChangeCallback callback) {
    onEvent.set(callback);
  }

  @Override
  public void unsubscribeSwitchEvent() {
    onEvent.set(null);
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
    // Nothing to flash.
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
}

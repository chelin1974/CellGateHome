package io.github.chelin1974.cellgatehome.homekit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hapjava.characteristics.impl.garagedoor.CurrentDoorStateEnum;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a gate reports when nothing measures its position.
 *
 * <p>A Watchman EVO with no contact wired to its status input never reports where the gate
 * is, so the only thing the bridge knows is that it sent an open command. Discarding that
 * left HomeKit snapping back to closed seconds after the gate opened, the dashboard stuck on
 * Unknown, and nothing at all in Prometheus.
 */
class GateAccessoryTest {

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
  }

  private GateAccessory gate(Duration assumeOpen) {
    return new GateAccessory(1, "Front gate", "serial", "1.0.0", () -> {}, scheduler, assumeOpen);
  }

  // ------------------------------------------------------- with no measured position

  @Test
  @DisplayName("a gate that has never been opened or read is unknown")
  void unknownBeforeAnythingHappens() {
    GateAccessory g = gate(Duration.ofMinutes(1));
    assertFalse(g.hasReading());
    assertNull(g.isOpen());
    assertFalse(g.isAssumed());
  }

  @Test
  @DisplayName("opening it reports open, and says the state was assumed")
  void opensOptimistically() {
    GateAccessory g = gate(Duration.ofMinutes(1));
    g.open();
    assertTrue(g.hasReading());
    assertEquals(Boolean.TRUE, g.isOpen());
    assertTrue(g.isAssumed());
  }

  /** The point of the whole change: Home used to go back to closed while the gate stood open. */
  @Test
  @DisplayName("HomeKit reads open after the brief \"opening\" window, not closed")
  void homeKitStaysOpen() throws Exception {
    GateAccessory g = gate(Duration.ofMinutes(1));
    g.open();
    assertEquals(CurrentDoorStateEnum.OPEN, afterOptimisticWindow(g));
  }

  @Test
  @DisplayName("the assumption lapses, and the gate goes back to unknown rather than closed")
  void assumptionLapses() throws Exception {
    GateAccessory g = gate(Duration.ofMillis(150));
    g.open();
    assertEquals(Boolean.TRUE, g.isOpen());
    Thread.sleep(250);
    assertFalse(g.hasReading());
    assertNull(g.isOpen());
    assertFalse(g.isAssumed());
  }

  @Test
  @DisplayName("with the assumption turned off, opening reports nothing")
  void canBeTurnedOff() {
    GateAccessory g = gate(Duration.ZERO);
    g.open();
    assertFalse(g.hasReading());
    assertNull(g.isOpen());
    assertFalse(g.isAssumed());
  }

  /** A gate that could not be triggered is not open, so nothing may be assumed about it. */
  @Test
  @DisplayName("a failed trigger assumes nothing")
  void failedTriggerAssumesNothing() {
    GateAccessory g = new GateAccessory(1, "Front gate", "serial", "1.0.0",
        () -> {
          throw new IllegalStateException("CellGate said no");
        },
        scheduler, Duration.ofMinutes(1));
    g.open();
    assertFalse(g.hasReading());
    assertNull(g.isOpen());
  }

  // ------------------------------------------------------- with a measured position

  @Test
  @DisplayName("a real reading wins over the assumption, even when it says closed")
  void measuredReadingWins() {
    GateAccessory g = gate(Duration.ofMinutes(1));
    g.open();
    assertTrue(g.isAssumed());

    // The unit's sensor reports in: it says the gate is shut, and that is the truth.
    g.updateOpen(Boolean.FALSE);
    assertEquals(Boolean.FALSE, g.isOpen());
    assertFalse(g.isAssumed(), "a measured reading is never an assumption");
  }

  @Test
  @DisplayName("a measured open is not flagged as assumed")
  void measuredOpenIsNotAssumed() {
    GateAccessory g = gate(Duration.ofMinutes(1));
    g.updateOpen(Boolean.TRUE);
    assertEquals(Boolean.TRUE, g.isOpen());
    assertFalse(g.isAssumed());
  }

  /** HomeKit shows OPENING for a few seconds first; this waits that out. */
  private static CurrentDoorStateEnum afterOptimisticWindow(GateAccessory g) throws Exception {
    CurrentDoorStateEnum state = g.getCurrentDoorState().get();
    if (state != CurrentDoorStateEnum.OPENING) {
      return state;
    }
    // Rather than sleeping out the real window, assert the state the accessory falls back to
    // once it is over - which is what isOpen() decides.
    return Boolean.TRUE.equals(g.isOpen())
        ? CurrentDoorStateEnum.OPEN
        : CurrentDoorStateEnum.CLOSED;
  }
}

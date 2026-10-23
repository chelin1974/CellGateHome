package io.github.chelin1974.cellgatehome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.chelin1974.cellgatehome.homekit.DoorbellWatcher;
import io.github.chelin1974.cellgatehome.homekit.GateDoorbell;
import io.github.hapjava.characteristics.impl.common.ProgrammableSwitchEnum;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The activity-log → doorbell logic: priming, matching, de-duplication and ordering. */
class DoorbellWatcherTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void stop() {
    scheduler.shutdownNow();
  }

  private GateDoorbell doorbell() {
    return new GateDoorbell(5, "Front gate Doorbell", "SER-1", "1.0.0", scheduler);
  }

  private DoorbellWatcher watcher(GateDoorbell bell) {
    return new DoorbellWatcher(
        List.of(new DoorbellWatcher.Bell("Front gate", bell)), List.of("Response Picture"));
  }

  /** A log page, newest first, built from (description) pairs with distinct timestamps. */
  private ArrayNode logOf(String... descriptions) {
    ArrayNode arr = MAPPER.createArrayNode();
    for (int i = 0; i < descriptions.length; i++) {
      ObjectNode e = MAPPER.createObjectNode();
      e.put("Description", descriptions[i]);
      e.put("ConvertedCreatedOn", "10/23/2026 01:" + (10 + i) + ":00 pm PST");
      e.put("User", "Guest (User)");
      e.put("IsSuccess", true);
      arr.add(e);
    }
    return arr;
  }

  @Test
  void firstBatchOnlyPrimesAndDoesNotRing() {
    DoorbellWatcher w = watcher(doorbell());
    assertEquals(0, w.onActivity(logOf("Internal Camera Response Picture", "Trigger Gate")));
  }

  @Test
  void aNewCameraPictureRingsOnce() {
    GateDoorbell bell = doorbell();
    DoorbellWatcher w = watcher(bell);
    w.onActivity(logOf("Trigger Gate")); // prime with no ring event
    int fired = w.onActivity(
        logOf("Internal Camera Response Picture", "Trigger Gate", "Checking non-local Code: 1122"));
    assertEquals(1, fired);
    assertEquals(ProgrammableSwitchEnum.SINGLE_PRESS, bell.getSwitchEvent().join());
  }

  @Test
  void theSameEntryIsNeverRungTwice() {
    DoorbellWatcher w = watcher(doorbell());
    w.onActivity(logOf("Trigger Gate"));
    ArrayNode visit = logOf("Internal Camera Response Picture", "Trigger Gate");
    assertEquals(1, w.onActivity(visit));
    assertEquals(0, w.onActivity(visit)); // same page polled again
  }

  @Test
  void ordinaryGateTrafficDoesNotRing() {
    DoorbellWatcher w = watcher(doorbell());
    w.onActivity(logOf("Trigger Gate"));
    assertEquals(0, w.onActivity(logOf("Gate Opened (hold for 1 hour)", "Gate Closed", "Trigger Gate")));
  }

  @Test
  void twoNewVisitsInOnePollRingTwice() {
    DoorbellWatcher w = watcher(doorbell());
    w.onActivity(logOf("Trigger Gate"));
    int fired = w.onActivity(logOf(
        "Internal Camera Response Picture", "Trigger Gate",
        "Internal Camera Response Picture", "Trigger Gate"));
    assertEquals(2, fired);
  }

  @Test
  void switchEventIsNullBetweenRings() {
    assertNull(doorbell().getSwitchEvent().join());
  }
}

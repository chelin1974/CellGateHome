package io.github.chelin1974.cellgatehome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.chelin1974.cellgatehome.cellgate.portal.ApiClient;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.LogEntry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
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

  private static final ObjectMapper MAPPER = new ApiClient().getObjectMapper();
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

  private int minute;

  /**
   * A log page, newest first, with a distinct timestamp on every entry ever made in the test,
   * and no IDs - so it exercises the description-and-time fallback that tells entries apart.
   * Built as JSON and read the way the generated client reads it.
   */
  private List<LogEntry> logOf(String... descriptions) {
    final ArrayNode arr = MAPPER.createArrayNode();
    for (final var description : descriptions) {
      final ObjectNode e = MAPPER.createObjectNode();
      e.put("Description", description);
      e.put("CreatedOn", "2026-10-23T13:%02d:00".formatted(minute++));
      e.putObject("User").put("Name", "Guest");
      e.put("IsSuccess", true);
      arr.add(e);
    }
    return MAPPER.convertValue(arr, new TypeReference<List<LogEntry>>() {});
  }

  @Test
  void firstBatchOnlyPrimesAndDoesNotRing() {
    final DoorbellWatcher w = watcher(doorbell());
    assertEquals(0, w.onActivity(logOf("Internal Camera Response Picture", "Trigger Gate")));
  }

  @Test
  void aNewCameraPictureRingsOnce() {
    final GateDoorbell bell = doorbell();
    final DoorbellWatcher w = watcher(bell);
    w.onActivity(logOf("Trigger Gate")); // prime with no ring event
    final int fired = w.onActivity(
        logOf("Internal Camera Response Picture", "Trigger Gate", "Checking non-local Code: 1122"));
    assertEquals(1, fired);
    assertEquals(ProgrammableSwitchEnum.SINGLE_PRESS, bell.getSwitchEvent().join());
  }

  @Test
  void theSameEntryIsNeverRungTwice() {
    final DoorbellWatcher w = watcher(doorbell());
    w.onActivity(logOf("Trigger Gate"));
    final List<LogEntry> visit = logOf("Internal Camera Response Picture", "Trigger Gate");
    assertEquals(1, w.onActivity(visit));
    assertEquals(0, w.onActivity(visit)); // same page polled again
  }

  @Test
  void ordinaryGateTrafficDoesNotRing() {
    final DoorbellWatcher w = watcher(doorbell());
    w.onActivity(logOf("Trigger Gate"));
    assertEquals(0, w.onActivity(logOf("Gate Opened (hold for 1 hour)", "Gate Closed", "Trigger Gate")));
  }

  @Test
  void twoNewVisitsInOnePollRingTwice() {
    final DoorbellWatcher w = watcher(doorbell());
    w.onActivity(logOf("Trigger Gate"));
    final int fired = w.onActivity(logOf(
        "Internal Camera Response Picture", "Trigger Gate",
        "Internal Camera Response Picture", "Trigger Gate"));
    assertEquals(2, fired);
  }

  @Test
  void switchEventIsNullBetweenRings() {
    assertNull(doorbell().getSwitchEvent().join());
  }
}

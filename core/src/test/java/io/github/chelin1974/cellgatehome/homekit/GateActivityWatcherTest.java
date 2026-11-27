package io.github.chelin1974.cellgatehome.homekit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.cellgatehome.cellgate.portal.ApiClient;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.LogEntry;
import java.time.Duration;
import java.util.List;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Activity-log entries → assumed-open windows on a gate that cannot measure its position. */
class GateActivityWatcherTest {

  private static final ObjectMapper MAPPER = new ApiClient().getObjectMapper();
  private static final long GATE_PORT = 78847;
  private static final long CAMERA_PORT = 78849;
  private static final Duration ASSUME = Duration.ofSeconds(60);

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final GateAccessory gate =
      new GateAccessory(2, "Front gate", "SER-1", "1.0.0", () -> {}, scheduler, ASSUME);
  private final GateActivityWatcher watcher =
      new GateActivityWatcher(Map.of(GATE_PORT, gate), ASSUME);
  private final ArrayNode page = MAPPER.createArrayNode();
  private long nextId = 1;

  @AfterEach
  void stop() {
    scheduler.shutdownNow();
  }

  /** The page as the generated client would hand it over. */
  private List<LogEntry> entries() {
    return MAPPER.convertValue(page, new TypeReference<List<LogEntry>>() {});
  }

  /** Prepends an entry, so the page stays newest first like the real log. */
  private void logged(Duration ago, long portId, String description) {
    ObjectNode e = MAPPER.createObjectNode();
    e.put("ID", nextId++);
    e.put("ModulePortId", portId);
    e.put("Description", description);
    e.put("IsSuccess", true);
    // CreatedOn is UTC with no zone, as CellGate sends it.
    e.put("CreatedOn", LocalDateTime.ofInstant(Instant.now().minus(ago), ZoneOffset.UTC).toString());
    page.insert(0, e);
  }

  @Test
  void aTriggerFromAnywhereIsAssumedOpen() {
    logged(Duration.ofSeconds(5), GATE_PORT, "Checking non-local Code: 1234");
    logged(Duration.ofSeconds(5), GATE_PORT, "Trigger Gate");

    assertEquals(1, watcher.onActivity(entries()));
    assertTrue(gate.isOpen());
    assertTrue(gate.isAssumed());
  }

  @Test
  void aTriggerOlderThanTheWindowIsAlreadyOver() {
    logged(Duration.ofMinutes(5), GATE_PORT, "Trigger Gate");

    assertEquals(0, watcher.onActivity(entries()));
    assertNull(gate.isOpen());
    assertFalse(gate.hasReading());
  }

  @Test
  void aHoldStillRunningAfterARestartComesBackOpen() {
    logged(Duration.ofHours(30), GATE_PORT, "Gate  Opened (hold for 88 hours)");
    logged(Duration.ofHours(2), GATE_PORT, "Gate is already opened");

    assertEquals(1, watcher.onActivity(entries()));
    assertTrue(gate.isOpen());
  }

  @Test
  void releasingTheHoldEndsIt() {
    logged(Duration.ofHours(30), GATE_PORT, "Gate  Opened (hold for 88 hours)");
    logged(Duration.ofHours(1), GATE_PORT, "Gate Closed");

    watcher.onActivity(entries());
    assertNull(gate.isOpen());
  }

  @Test
  void aCloseReadLateDoesNotCancelAnOpeningThatCameAfterIt() {
    logged(Duration.ofSeconds(30), GATE_PORT, "Gate Closed");
    gate.open(); // this bridge opened it after the close was logged

    watcher.onActivity(entries());
    assertTrue(gate.isOpen());
  }

  @Test
  void aShortTriggerDoesNotShortenARunningHold() {
    logged(Duration.ofHours(1), GATE_PORT, "Gate  Opened (hold for 88 hours)");
    logged(Duration.ofMinutes(10), GATE_PORT, "Trigger Gate");

    watcher.onActivity(entries());
    assertTrue(gate.isOpen());
  }

  @Test
  void otherPortsAndFailedCommandsAreIgnored() {
    logged(Duration.ofSeconds(5), CAMERA_PORT, "Trigger Gate");
    logged(Duration.ofSeconds(5), GATE_PORT, "Trigger Gate");
    ((ObjectNode) page.get(0)).put("IsSuccess", false);

    assertEquals(0, watcher.onActivity(entries()));
    assertNull(gate.isOpen());
  }

  @Test
  void anEntrySeenTwiceCountsOnce() {
    logged(Duration.ofSeconds(5), GATE_PORT, "Trigger Gate");

    assertEquals(1, watcher.onActivity(entries()));
    assertEquals(0, watcher.onActivity(entries()));
  }

  @Test
  void aMeasuredStateStillWins() {
    logged(Duration.ofSeconds(5), GATE_PORT, "Trigger Gate");
    watcher.onActivity(entries());
    gate.updateOpen(false);

    assertFalse(gate.isOpen());
    assertFalse(gate.isAssumed());
  }
}

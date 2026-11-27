package io.github.chelin1974.cellgatehome.homekit;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Assumes a gate open when the activity log says something opened it, for hardware that cannot
 * measure the gate's position.
 *
 * <p>The bridge already assumes a gate open for {@code assumeOpenSeconds} after it triggers the
 * gate itself, but most openings come from elsewhere: a keypad code, the CellGate app, a remote,
 * a hold-open schedule. Each of those leaves an entry on the gate port, and this reads them:
 *
 * <ul>
 *   <li>{@code Trigger Gate} - a momentary opening, from any source. Every accepted keypad code
 *       is followed by one. Open for {@code assumeOpenSeconds} from when it was logged.
 *   <li>{@code Gate Opened (hold for N hours)} - held open, typically by a schedule. Open until
 *       N hours after it was logged, or until a later {@code Gate Closed}.
 *   <li>{@code Gate Closed} - the hold was released. Ends the assumption.
 * </ul>
 *
 * <p>All windows are measured from the time CellGate logged the entry, not when the bridge read
 * it, so replaying old entries after a restart is harmless: a trigger from an hour ago is
 * already over, while a hold that started this morning correctly comes back as open. That is
 * why, unlike {@link DoorbellWatcher}, the first batch is not discarded.
 *
 * <p>An entry is matched to its gate by {@code ModulePortId}, the port that was driven, so
 * several gates on one account never get each other's openings.
 */
public final class GateActivityWatcher {

  private static final Logger log = LoggerFactory.getLogger(GateActivityWatcher.class);
  private static final int MAX_SEEN = 400;
  private static final Pattern HOLD =
      Pattern.compile("opened\\s*\\(hold for (\\d+) hours?\\)", Pattern.CASE_INSENSITIVE);

  private final Map<Long, GateAccessory> gatesByPort;
  private final Duration assumeOpen;
  private final Set<String> seen = new LinkedHashSet<>();

  /**
   * @param gatesByPort each gate's accessory, keyed by the ID of the port that opens it
   * @param assumeOpen how long a momentary opening counts as open
   */
  public GateActivityWatcher(Map<Long, GateAccessory> gatesByPort, Duration assumeOpen) {
    this.gatesByPort = Map.copyOf(gatesByPort);
    this.assumeOpen = assumeOpen;
  }

  /**
   * Feeds in the latest activity entries (newest first).
   *
   * @return how many gates went from not assumed open to assumed open, so the caller can record
   *     the opening before it lapses
   */
  public synchronized int onActivity(JsonNode entries) {
    if (entries == null || !entries.isArray()) {
      return 0;
    }
    List<JsonNode> list = new ArrayList<>();
    entries.forEach(list::add);
    int opened = 0;
    // Oldest first, so a hold followed by its release ends released.
    for (int i = list.size() - 1; i >= 0; i--) {
      JsonNode entry = list.get(i);
      String id = entry.path("ID").asString("");
      if (!id.isEmpty() && !seen.add(id)) {
        continue;
      }
      trimSeen();
      if (apply(entry)) {
        opened++;
      }
    }
    return opened;
  }

  private boolean apply(JsonNode entry) {
    GateAccessory gate = gatesByPort.get(entry.path("ModulePortId").asLong(0));
    if (gate == null || !entry.path("IsSuccess").asBoolean(true)) {
      return false;
    }
    Instant at = loggedAt(entry);
    String description = entry.path("Description").asString("");
    if (at == null || description.isBlank()) {
      return false;
    }
    String d = description.toLowerCase(Locale.ROOT);
    Matcher hold = HOLD.matcher(d);
    if (hold.find()) {
      log.debug("activity log: gate held open at {} for {} hours", at, hold.group(1));
      return gate.assumeOpen(at, at.plus(Duration.ofHours(Long.parseLong(hold.group(1)))));
    }
    if (d.contains("trigger gate")) {
      return !assumeOpen.isZero() && gate.assumeOpen(at, at.plus(assumeOpen));
    }
    if (d.contains("gate closed")) {
      gate.endAssumedOpen(at);
    }
    return false;
  }

  /** {@code CreatedOn} is UTC without a zone ({@code ConvertedCreatedOn} is local time). */
  static Instant loggedAt(JsonNode entry) {
    String created = entry.path("CreatedOn").asString("");
    if (created.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(created).toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private void trimSeen() {
    var it = seen.iterator();
    while (seen.size() > MAX_SEEN && it.hasNext()) {
      it.next();
      it.remove();
    }
  }
}

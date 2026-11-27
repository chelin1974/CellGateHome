package io.github.chelin1974.cellgatehome.homekit;

import io.github.chelin1974.cellgatehome.cellgate.portal.model.LogEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns new CellGate activity-log entries into HomeKit doorbell rings.
 *
 * <p>Each poll hands the recent log entries (newest first) to {@link #onActivity}. An entry rings a
 * doorbell when its description contains one of the configured "someone's here" phrases and the
 * bridge has not seen that entry before. The very first batch only primes the "seen" set, so a
 * restart never replays history as a flurry of notifications.
 *
 * <p>With one gate, every ring goes to that gate's doorbell. With several, an entry is matched to a
 * gate when the gate's location appears in the entry, and otherwise rings a lone doorbell if there
 * is exactly one.
 */
public final class DoorbellWatcher {

  private static final Logger log = LoggerFactory.getLogger(DoorbellWatcher.class);
  private static final int MAX_SEEN = 400;

  /** One gate's doorbell, with the location text used to attribute log entries to it. */
  public record Bell(String location, GateDoorbell doorbell) {}

  private final List<Bell> bells;
  private final List<String> ringPhrases;
  private final Set<String> seen = new LinkedHashSet<>();
  private boolean primed;

  public DoorbellWatcher(List<Bell> bells, List<String> ringPhrases) {
    this.bells = List.copyOf(bells);
    List<String> lower = new ArrayList<>();
    for (String p : ringPhrases) {
      lower.add(p.toLowerCase(Locale.ROOT));
    }
    this.ringPhrases = List.copyOf(lower);
  }

  /**
   * Feeds in the latest activity entries (newest first). Rings a doorbell for each new "someone's
   * here" entry. Returns how many rings were fired (zero while priming), for tests and logging.
   */
  public synchronized int onActivity(List<LogEntry> entries) {
    if (entries == null) {
      return 0;
    }
    // Walk oldest-first so, when several visits arrived between polls, they ring in order.
    List<LogEntry> list = new ArrayList<>(entries);
    int fired = 0;
    for (int i = list.size() - 1; i >= 0; i--) {
      LogEntry entry = list.get(i);
      String signature = signature(entry);
      if (!seen.add(signature)) {
        continue; // already handled
      }
      trimSeen();
      if (primed && isRing(entry)) {
        fired += ring(entry);
      }
    }
    if (!primed) {
      primed = true; // first batch only establishes the baseline
      return 0;
    }
    return fired;
  }

  private int ring(LogEntry entry) {
    GateDoorbell target = pick(location(entry));
    if (target == null) {
      return 0;
    }
    target.ring();
    log.debug("doorbell fired from log entry: {}", entry.description());
    return 1;
  }

  /** The unit's location name ("Front gate"), from the entry's module. */
  private static String location(LogEntry entry) {
    var module = entry.module();
    return module == null || module.propertyLocation() == null
        ? null : module.propertyLocation().name();
  }

  /** The doorbell for this entry: by location match, or the sole doorbell when there is one. */
  private GateDoorbell pick(String location) {
    if (bells.size() == 1) {
      return bells.get(0).doorbell();
    }
    if (location != null && !location.isBlank()) {
      String loc = location.toLowerCase(Locale.ROOT);
      for (Bell b : bells) {
        if (b.location() != null && !b.location().isBlank()
            && loc.contains(b.location().toLowerCase(Locale.ROOT))) {
          return b.doorbell();
        }
      }
    }
    return null; // ambiguous across several gates; skip rather than ring the wrong one
  }

  private boolean isRing(LogEntry entry) {
    String description = entry.description();
    if (description == null) {
      return false;
    }
    String d = description.toLowerCase(Locale.ROOT);
    for (String phrase : ringPhrases) {
      if (d.contains(phrase)) {
        return true;
      }
    }
    return false;
  }

  /** A stable fingerprint for an entry, so the same log line is never rung twice. */
  private static String signature(LogEntry entry) {
    if (entry.ID() != null) {
      return "id:" + entry.ID();
    }
    String who = entry.user() != null ? entry.user().name()
        : entry.adminUser() != null ? entry.adminUser().name() : null;
    return entry.description() + "|" + entry.createdOn() + "|" + who;
  }

  private void trimSeen() {
    if (seen.size() <= MAX_SEEN) {
      return;
    }
    var it = seen.iterator();
    while (seen.size() > MAX_SEEN && it.hasNext()) {
      it.next();
      it.remove();
    }
  }
}

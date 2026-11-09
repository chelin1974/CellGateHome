package io.github.chelin1974.cellgatehome.homekit;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Accessory names and firmware strings HomeKit will accept.
 *
 * <p>Recent iOS versions reject accessory names that do not start and end with a letter or
 * number, or that contain characters outside letters, numbers, spaces, apostrophes, periods,
 * commas and hyphens. CellGate lets you name a gate anything, so names are cleaned here.
 */
public final class HomeKitNames {

  private HomeKitNames() {}

  public static String clean(String raw, String fallback) {
    // Curly quotes are common in names typed on a phone ("Maya’s Room"); HomeKit only takes '.
    String cleaned = raw == null ? "" : raw.replaceAll("[\u2018\u2019\u02BC]", "'");
    cleaned = cleaned.replaceAll("[^\\p{L}\\p{N} '.,\\-]", " ");
    cleaned = cleaned.replaceAll("\\s+", " ");
    cleaned = cleaned.replaceAll("^[^\\p{L}\\p{N}]+", "").replaceAll("[^\\p{L}\\p{N}]+$", "");
    if (cleaned.length() > 64) {
      cleaned = cleaned.substring(0, 64).replaceAll("[^\\p{L}\\p{N}]+$", "");
    }
    return cleaned.isEmpty() ? fallback : cleaned;
  }

  /** Makes {@code name} unique among {@code taken} by appending " 2", " 3", ... */
  public static String unique(String name, Collection<String> taken) {
    Set<String> lower = new HashSet<>();
    taken.forEach(t -> lower.add(t.toLowerCase(Locale.ROOT)));
    if (!lower.contains(name.toLowerCase(Locale.ROOT))) {
      return name;
    }
    for (int n = 2; ; n++) {
      String candidate = name + " " + n;
      if (!lower.contains(candidate.toLowerCase(Locale.ROOT))) {
        return candidate;
      }
    }
  }

  /**
   * HomeKit wants a firmware revision of the form {@code x.y.z}. Keep the first three numbers
   * of whatever the device reports, e.g. {@code v2.4.17-b3} becomes {@code 2.4.17}.
   */
  public static String firmware(String raw) {
    if (raw == null) {
      return "1.0.0";
    }
    String[] digits = raw.replaceAll("[^0-9]+", " ").trim().split(" ");
    if (digits.length == 0 || digits[0].isEmpty()) {
      return "1.0.0";
    }
    StringBuilder version = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      if (i > 0) {
        version.append('.');
      }
      version.append(i < digits.length ? Long.parseLong(digits[i]) : 0);
    }
    return version.toString();
  }
}

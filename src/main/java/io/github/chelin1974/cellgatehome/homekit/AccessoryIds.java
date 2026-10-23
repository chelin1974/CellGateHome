package io.github.chelin1974.cellgatehome.homekit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stable HomeKit accessory IDs, keyed by circuit or device ID.
 *
 * <p>HomeKit remembers each accessory by its numeric ID: rooms, names and automations are tied
 * to it. Numbering by discovery order would reshuffle them whenever a circuit is added or
 * renamed, so each ID is assigned once and kept in a file.
 */
public final class AccessoryIds {

  /** HomeKit reserves ID 1 for the bridge itself. */
  static final int FIRST_ID = 2;

  private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private final Path file;
  private final Map<String, Integer> ids;
  private boolean dirty;

  private AccessoryIds(Path file, Map<String, Integer> ids) {
    this.file = file;
    this.ids = ids;
  }

  public static AccessoryIds load(Path file) {
    if (!Files.exists(file)) {
      return new AccessoryIds(file, new TreeMap<>());
    }
    try {
      return new AccessoryIds(file, new TreeMap<>(MAPPER.readValue(Files.readString(file), new TypeReference<Map<String, Integer>>() {})));
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + file.toAbsolutePath(), e);
    }
  }

  /** The ID for {@code key}, assigning the next free one the first time. */
  public synchronized int idFor(String key) {
    Integer existing = ids.get(key);
    if (existing != null) {
      return existing;
    }
    int next = ids.values().stream().mapToInt(Integer::intValue).max().orElse(FIRST_ID - 1) + 1;
    ids.put(key, next);
    dirty = true;
    return next;
  }

  /** Writes new assignments, if any. */
  public synchronized void save() {
    if (!dirty) {
      return;
    }
    try {
      Path absolute = file.toAbsolutePath();
      Path directory = absolute.getParent();
      if (directory != null) {
        Files.createDirectories(directory);
      }
      Path temp = Files.createTempFile(directory, ".cellgate-accessories", ".tmp");
      Files.writeString(temp, MAPPER.writeValueAsString(ids));
      Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      dirty = false;
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + file.toAbsolutePath(), e);
    }
  }
}

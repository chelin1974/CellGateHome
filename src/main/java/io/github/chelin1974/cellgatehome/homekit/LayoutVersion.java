package io.github.chelin1974.cellgatehome.homekit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hapjava.accessories.HomekitAccessory;
import io.github.hapjava.characteristics.Characteristic;
import io.github.hapjava.services.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps HomeKit's configuration number in step with the accessory layout.
 *
 * <p>iOS caches a bridge's accessories and services, and only reloads them when the
 * configuration number ({@code c#}) it advertises goes up. HAP-Java always advertises 1, so
 * an added circuit, or a new sensor on an existing one, would never appear in the Home app.
 * This fingerprints the layout and bumps the number whenever the fingerprint changes.
 */
public final class LayoutVersion {

  private static final Logger log = LoggerFactory.getLogger(LayoutVersion.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** HomeKit's c# runs from 1 to 65535. */
  static final int MAX = 65535;

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class State {
    public String fingerprint;
    public int configurationIndex = 1;
  }

  private LayoutVersion() {}

  /** The configuration number to advertise for {@code accessories}, persisted in {@code file}. */
  public static int resolve(Path file, List<HomekitAccessory> accessories) {
    String fingerprint = fingerprint(accessories);
    State state = read(file);
    if (fingerprint.equals(state.fingerprint)) {
      log.info("HomeKit configuration {} (accessory layout unchanged)", state.configurationIndex);
      return state.configurationIndex;
    }
    boolean first = state.fingerprint == null;
    // A bridge paired before this file existed was advertised as 1, so start at 2: that makes
    // iOS reload it once, and is harmless for a new bridge.
    state.configurationIndex = first ? 2 : (state.configurationIndex >= MAX ? 1 : state.configurationIndex + 1);
    state.fingerprint = fingerprint;
    try {
      Files.writeString(file, MAPPER.writeValueAsString(state));
    } catch (IOException e) {
      log.warn("could not save {}: {}", file, e.getMessage());
    }
    if (first) {
      log.info("HomeKit configuration {} (first layout recorded; iOS reloads the accessories once)", state.configurationIndex);
    } else {
      log.info("accessory layout changed; HomeKit configuration is now {}, so iOS reloads it", state.configurationIndex);
    }
    return state.configurationIndex;
  }

  /** Accessory IDs, names, service types and characteristic kinds, in a stable order. */
  static String fingerprint(List<HomekitAccessory> accessories) {
    StringBuilder layout = new StringBuilder();
    accessories.stream()
        .sorted(Comparator.comparingInt(HomekitAccessory::getId))
        .forEach(accessory -> {
          layout.append(accessory.getId()).append(':').append(accessory.getName().join()).append('{');
          for (Service service : accessory.getServices()) {
            appendService(layout, service);
            for (Service linked : service.getLinkedServices()) {
              layout.append('>');
              appendService(layout, linked);
            }
          }
          layout.append('}');
        });
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(layout.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      return Integer.toHexString(layout.toString().hashCode());
    }
  }

  private static void appendService(StringBuilder layout, Service service) {
    layout.append(service.getType()).append('[');
    for (Characteristic characteristic : service.getCharacteristics()) {
      layout.append(characteristic.getClass().getName()).append(',');
    }
    layout.append(']');
  }

  private static State read(Path file) {
    if (!Files.exists(file)) {
      return new State();
    }
    try {
      return MAPPER.readValue(Files.readString(file), State.class);
    } catch (IOException e) {
      return new State();
    }
  }
}

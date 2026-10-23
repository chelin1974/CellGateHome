package io.github.chelin1974.cellgatehome.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.chelin1974.cellgatehome.cellgate.CellGateClient;
import io.github.chelin1974.cellgatehome.cellgate.Watchman;
import io.github.chelin1974.cellgatehome.homekit.GateAccessory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the dashboard shows and does: the gates and their live state, the latest camera picture,
 * and the recent activity log. Switching a gate open goes through the same {@link GateAccessory}
 * the Home app drives, so Home and the page stay in step.
 */
public final class DashboardModel {

  /** One gate on the dashboard: its accessory plus where it lives, for display. */
  public record Gate(String id, String name, String location, GateAccessory accessory) {}

  public enum OpenResult {
    OK,
    NOT_FOUND,
    FAILED
  }

  private final List<Gate> gates;
  private final CellGateClient client;
  private final List<Watchman> units;

  public DashboardModel(List<Gate> gates, List<Watchman> units, CellGateClient client) {
    this.gates = List.copyOf(gates);
    this.units = List.copyOf(units);
    this.client = client;
  }

  public List<Gate> gates() {
    return gates;
  }

  /** The gates and their state, shaped for the page. */
  public Map<String, Object> state(boolean switchingEnabled) {
    List<Object> list = new ArrayList<>();
    for (Gate g : gates) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", g.id());
      m.put("name", g.name());
      m.put("location", g.location());
      Boolean open = g.accessory().isOpen();
      m.put("state", !g.accessory().hasReading() ? "unknown" : open ? "open" : "closed");
      list.add(m);
    }
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("time", System.currentTimeMillis());
    state.put("switching", switchingEnabled);
    state.put("gates", list);
    return state;
  }

  /** Opens a gate through its accessory. */
  public OpenResult open(String gateId) {
    for (Gate g : gates) {
      if (g.id().equals(gateId)) {
        try {
          g.accessory().open();
          return OpenResult.OK;
        } catch (RuntimeException e) {
          return OpenResult.FAILED;
        }
      }
    }
    return OpenResult.NOT_FOUND;
  }

  /** Asks every camera to take a fresh picture. */
  public void takePictures() {
    for (Watchman unit : units) {
      unit.camera().ifPresent(cam -> client.takePicture(unit, cam));
    }
  }

  /** The recent activity entries from CellGate. */
  public JsonNode activity(int count) {
    return client.activity(count);
  }

  /** The latest picture for each camera: {@code {portId, name, attachment, takenAt}}. */
  public List<Map<String, Object>> cameras() {
    List<Long> cameraPorts = new ArrayList<>();
    Map<Long, String> names = new LinkedHashMap<>();
    Integer timezone = null;
    for (Watchman unit : units) {
      if (timezone == null) {
        timezone = unit.timezoneId();
      }
      unit.camera().ifPresent(cam -> {
        cameraPorts.add(cam.id());
        names.put(cam.id(), cam.name());
      });
    }
    if (cameraPorts.isEmpty()) {
      return List.of();
    }
    JsonNode images = client.cameraImages(cameraPorts, timezone);
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode entry : images) {
      JsonNode data = entry.path("Data");
      long portId = entry.path("ModulePortID").asLong();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("portId", portId);
      m.put("name", names.getOrDefault(portId, "Camera"));
      m.put("attachment", data.path("Attachments").asText(null));
      m.put("takenAt", data.path("ConvertedCreatedOn").asText(null));
      out.add(m);
    }
    return out;
  }

  /** The bytes of a stored image by attachment name, for the dashboard to serve. */
  public Optional<byte[]> image(String attachment) {
    return client.image(attachment);
  }
}

package io.github.chelin1974.cellgatehome.web;

import io.github.chelin1974.cellgatehome.cellgate.portal.model.LogEntry;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.PortImage;
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
    final var list = new ArrayList<Object>();
    for (final var g : gates) {
      final var m = new LinkedHashMap<String, Object>();
      m.put("id", g.id());
      m.put("name", g.name());
      m.put("location", g.location());
      final Boolean open = g.accessory().isOpen();
      m.put("state", !g.accessory().hasReading() ? "unknown" : open ? "open" : "closed");
      // Whether that came from the gate's sensor or from the trigger we sent, so the page can
      // say which instead of presenting an assumption as a reading.
      m.put("assumed", g.accessory().isAssumed());
      list.add(m);
    }
    final var state = new LinkedHashMap<String, Object>();
    state.put("time", System.currentTimeMillis());
    state.put("switching", switchingEnabled);
    state.put("gates", list);
    return state;
  }

  /** Opens a gate through its accessory. */
  public OpenResult open(String gateId) {
    for (final var g : gates) {
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
    for (final var unit : units) {
      unit.camera().ifPresent(cam -> client.takePicture(unit, cam));
    }
  }

  /** The recent activity entries from CellGate, newest first. */
  public List<LogEntry> activity(int count) {
    return client.activity(count);
  }

  /** The latest picture for each camera: {@code {portId, name, attachment, takenAt}}. */
  public List<Map<String, Object>> cameras() {
    final var cameraPorts = new ArrayList<Long>();
    final var names = new LinkedHashMap<Long, String>();
    Integer timezone = null;
    for (final var unit : units) {
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
    final var out = new ArrayList<Map<String, Object>>();
    for (final var entry : client.cameraImages(cameraPorts, timezone)) {
      final long portId = entry.modulePortID() == null ? 0L : entry.modulePortID();
      final var data = entry.data();
      final var m = new LinkedHashMap<String, Object>();
      m.put("portId", portId);
      m.put("name", names.getOrDefault(portId, "Camera"));
      m.put("attachment", data == null ? null : data.attachments());
      m.put("takenAt", data == null ? null : data.convertedCreatedOn());
      out.add(m);
    }
    return out;
  }

  /** The bytes of a stored image by attachment name, for the dashboard to serve. */
  public Optional<byte[]> image(String attachment) {
    return client.image(attachment);
  }

  /**
   * The latest stored picture as JPEG bytes, for a plain {@code /snapshot.jpg} that tools such as
   * Homebridge's camera-ffmpeg can poll. With a {@code portId} it picks that camera, otherwise the
   * first one. This is the most recent still CellGate holds, not a fresh capture.
   */
  public Optional<byte[]> latestSnapshot(Long portId) {
    final List<Map<String, Object>> cams = cameras();
    if (cams.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> chosen = null;
    if (portId != null) {
      for (final var cam : cams) {
        final Object id = cam.get("portId");
        if (id instanceof Number n && n.longValue() == portId) {
          chosen = cam;
          break;
        }
      }
    }
    if (chosen == null) {
      chosen = cams.get(0);
    }
    final Object attachment = chosen.get("attachment");
    return attachment == null ? Optional.empty() : image(attachment.toString());
  }
}

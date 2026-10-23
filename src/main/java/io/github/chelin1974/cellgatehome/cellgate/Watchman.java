package io.github.chelin1974.cellgatehome.cellgate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One CellGate unit (a "module") and its ports, as the portal's {@code Dashboard/getModules}
 * returns them. Pure: built from JSON, no network.
 *
 * <p>A Watchman EVO single-family unit has an output port that opens the gate ("Watchman
 * Gate", entry type Gate), an input port that can report the gate's position ("Gate Status",
 * entry type Sensor) and a camera port ("Internal Camera", entry type Camera, port 11).
 */
public record Watchman(
    long id,
    long modemId,
    String serial,
    String hardwareModel,
    String modelName,
    String location,
    long propertyLocationId,
    Integer timezoneId,
    boolean online,
    List<Port> ports) {

  /** {@code LastStateTypeID}: 1 is the relay's or input's low state, 2 its high state. */
  public static final int STATE_LOW = 1;
  public static final int STATE_HIGH = 2;

  public record Port(
      long id,
      String name,
      String number,
      String entryType,
      String portTypeCode,
      Integer lastState,
      String lowStatus,
      String highStatus,
      Long parentId,
      boolean holding,
      boolean liveStream) {

    public boolean isGate() {
      return "Gate".equalsIgnoreCase(entryType) && "O".equalsIgnoreCase(portTypeCode);
    }

    public boolean isCamera() {
      return "Camera".equalsIgnoreCase(entryType);
    }

    public boolean isSensor() {
      return "Sensor".equalsIgnoreCase(entryType) && "I".equalsIgnoreCase(portTypeCode);
    }

    /** The portal's text for the current state, e.g. "Gate Closed", or null when unknown. */
    public String statusText() {
      if (lastState == null) {
        return null;
      }
      return lastState == STATE_LOW ? lowStatus : lastState == STATE_HIGH ? highStatus : null;
    }
  }

  public static List<Watchman> parse(JsonNode modules) {
    List<Watchman> out = new ArrayList<>();
    for (JsonNode m : modules) {
      List<Port> ports = new ArrayList<>();
      for (JsonNode p : m.path("ModulePorts")) {
        if (p.path("IsDeleted").asBoolean(false)) {
          continue;
        }
        ports.add(new Port(
            p.path("ID").asLong(),
            text(p, "Name"),
            text(p, "PortNumber"),
            text(p.path("EntryType"), "Name"),
            text(p.path("PortType"), "Code"),
            p.hasNonNull("LastStateTypeID") ? p.get("LastStateTypeID").asInt() : null,
            text(p, "RelayLowStatus"),
            text(p, "RelayHighStatus"),
            p.hasNonNull("ParentID") ? p.get("ParentID").asLong() : null,
            p.path("IsHoldOpenStatus").asBoolean(false) || p.path("IsHolding").asBoolean(false),
            p.path("IsSupportLiveStream").asBoolean(false)));
      }
      JsonNode location = m.path("PropertyLocation");
      out.add(new Watchman(
          m.path("ID").asLong(),
          m.path("ModuleModemID").asLong(),
          text(m, "DeviceSerialNumber"),
          text(m.path("KitHardwareType"), "Model"),
          text(m.path("KitModelType"), "Name"),
          text(location, "Name"),
          m.path("PropertyLocationID").asLong(),
          location.hasNonNull("TimezoneID") ? location.get("TimezoneID").asInt() : null,
          m.path("IsOnline").asBoolean(true),
          List.copyOf(ports)));
    }
    return out;
  }

  public List<Port> gates() {
    return ports.stream().filter(Port::isGate).toList();
  }

  public Optional<Port> camera() {
    return ports.stream().filter(Port::isCamera).findFirst();
  }

  public Optional<Port> port(long portId) {
    return ports.stream().filter(p -> p.id() == portId).findFirst();
  }

  /** The input that reports this gate's position: its parent, or else the unit's only sensor. */
  public Optional<Port> statusSensorFor(Port gate) {
    if (gate.parentId() != null) {
      Optional<Port> parent = port(gate.parentId()).filter(Port::isSensor);
      if (parent.isPresent()) {
        return parent;
      }
    }
    List<Port> sensors = ports.stream().filter(Port::isSensor).toList();
    return sensors.size() == 1 ? Optional.of(sensors.get(0)) : Optional.empty();
  }

  /** Whether this is a Watchman EVO, the hardware this bridge has been checked against. */
  public boolean isEvo() {
    return "WTM-EVO".equalsIgnoreCase(hardwareModel);
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isMissingNode() || v.isNull() ? null : v.asText().trim();
  }
}

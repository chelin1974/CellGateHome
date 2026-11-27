package io.github.chelin1974.cellgatehome.cellgate;

import io.github.chelin1974.cellgatehome.cellgate.portal.model.Module;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.ModulePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One CellGate unit (a "module") and its ports, as the portal's {@code Dashboard/getModules}
 * returns them. Pure: built from the generated {@code Module} records, no network.
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

  /** Builds the units from {@code getModules}' reply, skipping deleted ports. */
  public static List<Watchman> from(List<Module> modules) {
    final var out = new ArrayList<Watchman>();
    for (final var m : modules == null ? List.<Module>of() : modules) {
      final var ports = new ArrayList<Port>();
      for (final var p : m.modulePorts() == null ? List.<ModulePort>of() : m.modulePorts()) {
        if (Boolean.TRUE.equals(p.isDeleted())) {
          continue;
        }
        ports.add(new Port(
            orZero(p.ID()),
            trim(p.name()),
            trim(p.portNumber()),
            p.entryType() == null ? null : trim(p.entryType().name()),
            p.portType() == null ? null : trim(p.portType().code()),
            p.lastStateTypeID(),
            trim(p.relayLowStatus()),
            trim(p.relayHighStatus()),
            p.parentID(),
            Boolean.TRUE.equals(p.isHoldOpenStatus()) || Boolean.TRUE.equals(p.isHolding()),
            Boolean.TRUE.equals(p.isSupportLiveStream())));
      }
      final var location = m.propertyLocation();
      out.add(new Watchman(
          orZero(m.ID()),
          orZero(m.moduleModemID()),
          trim(m.deviceSerialNumber()),
          m.kitHardwareType() == null ? null : trim(m.kitHardwareType().model()),
          m.kitModelType() == null ? null : trim(m.kitModelType().name()),
          location == null ? null : trim(location.name()),
          orZero(m.propertyLocationID()),
          location == null ? null : location.timezoneID(),
          // A missing IsOnline reads as online, as the portal treats it.
          !Boolean.FALSE.equals(m.isOnline()),
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
      final Optional<Port> parent = port(gate.parentId()).filter(Port::isSensor);
      if (parent.isPresent()) {
        return parent;
      }
    }
    final List<Port> sensors = ports.stream().filter(Port::isSensor).toList();
    return sensors.size() == 1 ? Optional.of(sensors.get(0)) : Optional.empty();
  }

  /** Whether this is a Watchman EVO, the hardware this bridge has been checked against. */
  public boolean isEvo() {
    return "WTM-EVO".equalsIgnoreCase(hardwareModel);
  }

  private static String trim(String s) {
    return s == null ? null : s.trim();
  }

  private static long orZero(Long v) {
    return v == null ? 0L : v;
  }
}

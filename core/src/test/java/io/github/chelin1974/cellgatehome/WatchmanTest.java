package io.github.chelin1974.cellgatehome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import io.github.chelin1974.cellgatehome.cellgate.Watchman;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WatchmanTest {

  /** A trimmed-down getModules reply for one Watchman EVO single-family unit. */
  static final String JSON = """
      [{"ID":64277,"ModuleModemID":15841,"DeviceSerialNumber":"0C:11:05:10:F5:32","IsOnline":true,
        "KitHardwareType":{"Model":"WTM-EVO"},"KitModelType":{"Name":"WTM-S2"},
        "PropertyLocation":{"Name":"Front gate","TimezoneID":1},
        "ModulePorts":[
          {"ID":78848,"Name":"Gate Status","PortNumber":"01","EntryType":{"Name":"Sensor"},
           "PortType":{"Code":"I"},"LastStateTypeID":1,"RelayLowStatus":"Closed","RelayHighStatus":"Open"},
          {"ID":78847,"Name":"Watchman Gate","PortNumber":"01","EntryType":{"Name":"Gate"},
           "PortType":{"Code":"O"},"ParentID":78848,"LastStateTypeID":1,
           "RelayLowStatus":"Gate Closed","RelayHighStatus":"Gate Open"},
          {"ID":78849,"Name":"Internal Camera","PortNumber":"11","EntryType":{"Name":"Camera"},
           "PortType":{"Code":"O"}},
          {"ID":78850,"Name":"Remote Receiver","PortNumber":"1W","EntryType":{"Name":"RF/Remote Receiver"},
           "PortType":{"Code":"W"}}]}]""";

  @Test
  @DisplayName("parses the unit, its gate, camera and the status sensor that reports the gate")
  void parse() throws Exception {
    List<Watchman> units = Watchman.parse(new ObjectMapper().readTree(JSON));
    assertEquals(1, units.size());
    Watchman u = units.get(0);
    assertEquals("Front gate", u.location());
    assertEquals("WTM-EVO", u.hardwareModel());
    assertTrue(u.isEvo());
    assertEquals(64277, u.id());
    assertEquals(15841, u.modemId());

    assertEquals(1, u.gates().size());
    Watchman.Port gate = u.gates().get(0);
    assertEquals("Watchman Gate", gate.name());
    assertEquals(78848, u.statusSensorFor(gate).orElseThrow().id());
    assertTrue(u.camera().isPresent());
    assertEquals("Internal Camera", u.camera().get().name());

    // LastStateTypeID 1 is the low state, so the gate reads "Gate Closed".
    assertEquals("Gate Closed", gate.statusText());
    assertEquals("Closed", u.statusSensorFor(gate).orElseThrow().statusText());
  }

  @Test
  @DisplayName("the status text maps to open/closed; anything else is unknown")
  void looksOpen() {
    assertEquals(Boolean.TRUE, CellGateHomeBridge.looksOpen("Gate Open"));
    assertEquals(Boolean.FALSE, CellGateHomeBridge.looksOpen("Gate Closed"));
    assertEquals(Boolean.TRUE, CellGateHomeBridge.looksOpen("Open"));
    assertEquals(null, CellGateHomeBridge.looksOpen("Unknown"));
    assertEquals(null, CellGateHomeBridge.looksOpen(null));
  }

  @Test
  @DisplayName("discoverGates finds the gate and links its status sensor")
  void discover() throws Exception {
    List<Watchman> units = Watchman.parse(new ObjectMapper().readTree(JSON));
    var gates = CellGateHomeBridge.discoverGates(units);
    assertEquals(1, gates.size());
    assertEquals("Front gate", gates.get(0).name());
    assertEquals(Long.valueOf(78848L), gates.get(0).statusPortId());
    assertFalse(gates.get(0).unit().camera().isEmpty());
  }

  // ------------------------------------------------- the sensor that reports nothing

  /**
   * The same unit as {@link #JSON}, except the status sensor carries no {@code
   * LastStateTypeID} - which is what a real Watchman EVO sends. The sensor is still
   * discovered and still linked to the gate; it simply never reports a state, so the only
   * live text on the unit is the gate port's own.
   */
  static final String SILENT_SENSOR_JSON = """
      [{"ID":64277,"ModuleModemID":15841,"DeviceSerialNumber":"0C:11:05:10:F5:32","IsOnline":true,
        "KitHardwareType":{"Model":"WTM-EVO"},"KitModelType":{"Name":"WTM-S2"},
        "PropertyLocation":{"Name":"Front gate","TimezoneID":1},
        "ModulePorts":[
          {"ID":78848,"Name":"Gate Status","PortNumber":"01","EntryType":{"Name":"Sensor"},
           "PortType":{"Code":"I"},"RelayLowStatus":"Closed","RelayHighStatus":"Open"},
          {"ID":78847,"Name":"Watchman Gate","PortNumber":"01","EntryType":{"Name":"Gate"},
           "PortType":{"Code":"O"},"ParentID":78848,"LastStateTypeID":1,
           "RelayLowStatus":"Gate Closed","RelayHighStatus":"Gate Open"},
          {"ID":78849,"Name":"Internal Camera","PortNumber":"11","EntryType":{"Name":"Camera"},
           "PortType":{"Code":"O"}}]}]""";

  @Test
  @DisplayName("a status sensor that reports no state leaves the gate port as the only reading")
  void silentSensorStillParses() throws Exception {
    Watchman u = Watchman.parse(new ObjectMapper().readTree(SILENT_SENSOR_JSON)).get(0);
    Watchman.Port gate = u.gates().get(0);

    // Discovered and linked, exactly as before - this was never a discovery failure.
    assertEquals(78848, u.statusSensorFor(gate).orElseThrow().id());
    assertEquals(null, u.statusSensorFor(gate).orElseThrow().statusText());
    assertEquals("Gate Closed", gate.statusText());
  }

  /**
   * The gate port is an output driven by a momentary relay pulse, so its text is the relay's
   * state and reads "Gate Closed" except during the pulse. Using it as a position would make
   * this gate report a constant "closed" even while standing open - a false all-clear, which
   * for a gate is worse than admitting the position is unknown. So it stays unknown.
   */
  @Test
  @DisplayName("a silent sensor leaves the gate unknown rather than trusting the output port")
  void silentSensorIsUnknownNotClosed() throws Exception {
    Watchman u = Watchman.parse(new ObjectMapper().readTree(SILENT_SENSOR_JSON)).get(0);
    assertEquals("Gate Closed", u.port(78847L).orElseThrow().statusText());
    assertEquals(null, CellGateHomeBridge.openStateFor(u, 78848L));
  }

  @Test
  @DisplayName("a status sensor that reports a state is used")
  void usesTheStatusSensor() throws Exception {
    // The sensor says "Open" (high) while the gate port says "Gate Closed" (low).
    String json = JSON.replace(
        "\"PortType\":{\"Code\":\"I\"},\"LastStateTypeID\":1",
        "\"PortType\":{\"Code\":\"I\"},\"LastStateTypeID\":2");
    Watchman u = Watchman.parse(new ObjectMapper().readTree(json)).get(0);
    assertEquals("Open", u.port(78848L).orElseThrow().statusText());
    assertEquals(Boolean.TRUE, CellGateHomeBridge.openStateFor(u, 78848L));
  }

  @Test
  @DisplayName("the sensor reporting closed is used as-is")
  void sensorClosed() throws Exception {
    Watchman u = Watchman.parse(new ObjectMapper().readTree(JSON)).get(0);
    assertEquals("Closed", u.port(78848L).orElseThrow().statusText());
    assertEquals(Boolean.FALSE, CellGateHomeBridge.openStateFor(u, 78848L));
  }

  @Test
  @DisplayName("a gate with no status sensor at all is unknown")
  void noSensorAtAll() throws Exception {
    Watchman u = Watchman.parse(new ObjectMapper().readTree(SILENT_SENSOR_JSON)).get(0);
    assertEquals(null, CellGateHomeBridge.openStateFor(u, null));
  }
}

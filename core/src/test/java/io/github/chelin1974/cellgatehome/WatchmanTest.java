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
   * The bug this guards: reading only the sensor gave no state at all, which showed up as a
   * dashboard stuck on "Unknown" and - because a gate with no reading is skipped when
   * collecting metrics - an empty Grafana panel with nothing logged to explain it.
   */
  @Test
  @DisplayName("the gate port's own text is used when the status sensor reports none")
  void fallsBackToTheGatePort() throws Exception {
    Watchman u = Watchman.parse(new ObjectMapper().readTree(SILENT_SENSOR_JSON)).get(0);
    assertEquals(Boolean.FALSE, CellGateHomeBridge.openStateFor(u, 78848L, 78847L));
  }

  @Test
  @DisplayName("a status sensor that does report a state still wins")
  void prefersTheStatusSensor() throws Exception {
    // The sensor says "Open" (high) while the gate port says "Gate Closed" (low), so a result
    // of true proves the sensor was read rather than the fallback.
    String json = JSON.replace(
        "\"PortType\":{\"Code\":\"I\"},\"LastStateTypeID\":1",
        "\"PortType\":{\"Code\":\"I\"},\"LastStateTypeID\":2");
    Watchman u = Watchman.parse(new ObjectMapper().readTree(json)).get(0);
    assertEquals("Open", u.port(78848L).orElseThrow().statusText());
    assertEquals("Gate Closed", u.port(78847L).orElseThrow().statusText());
    assertEquals(Boolean.TRUE, CellGateHomeBridge.openStateFor(u, 78848L, 78847L));
  }

  @Test
  @DisplayName("neither port reporting anything is still unknown")
  void stillUnknownWhenNothingReports() throws Exception {
    String json = SILENT_SENSOR_JSON.replace("\"LastStateTypeID\":1,", "");
    Watchman u = Watchman.parse(new ObjectMapper().readTree(json)).get(0);
    assertEquals(null, CellGateHomeBridge.openStateFor(u, 78848L, 78847L));
  }

  @Test
  @DisplayName("a gate with no status sensor at all falls back to its own text")
  void noSensorAtAll() throws Exception {
    Watchman u = Watchman.parse(new ObjectMapper().readTree(SILENT_SENSOR_JSON)).get(0);
    assertEquals(Boolean.FALSE, CellGateHomeBridge.openStateFor(u, null, 78847L));
  }
}

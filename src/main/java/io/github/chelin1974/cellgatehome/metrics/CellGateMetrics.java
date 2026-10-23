package io.github.chelin1974.cellgatehome.metrics;

import io.github.chelin1974.cellgatehome.web.DashboardModel;
import io.github.chelin1974.cellgatehome.web.DashboardModel.Gate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the gate state into an OTLP metrics request (the JSON encoding of
 * {@code ExportMetricsServiceRequest}). Pure: no network, no clock.
 *
 * <p>Prometheus' OTLP receiver turns {@code cellgate.gate.open} into {@code cellgate_gate_open}
 * and attribute {@code gate} into label {@code gate}. The one gauge is 1 while the gate is open,
 * 0 while closed; a gate whose state is unknown is left out, so no series is invented.
 */
public final class CellGateMetrics {

  public record Point(String name, String unit, String description, double value, Map<String, String> attributes) {}

  private CellGateMetrics() {}

  public static List<Point> collect(DashboardModel model) {
    List<Point> points = new ArrayList<>();
    for (Gate g : model.gates()) {
      Boolean open = g.accessory().isOpen();
      if (!g.accessory().hasReading() || open == null) {
        continue;
      }
      Map<String, String> attrs = new LinkedHashMap<>();
      attrs.put("gate", g.name());
      if (g.location() != null && !g.location().isBlank()) {
        attrs.put("location", g.location());
      }
      points.add(new Point("cellgate.gate.open", "", "1 while the gate is open, 0 while closed",
          open ? 1 : 0, attrs));
    }
    return points;
  }

  public static Map<String, Object> request(List<Point> points, long nowNanos, Map<String, String> resource, String version) {
    Map<String, Map<String, Object>> metrics = new LinkedHashMap<>();
    for (Point point : points) {
      Map<String, Object> metric = metrics.computeIfAbsent(point.name(), name -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("unit", point.unit());
        m.put("description", point.description());
        Map<String, Object> gauge = new LinkedHashMap<>();
        gauge.put("dataPoints", new ArrayList<>());
        m.put("gauge", gauge);
        return m;
      });
      @SuppressWarnings("unchecked")
      List<Object> dataPoints = (List<Object>) ((Map<String, Object>) metric.get("gauge")).get("dataPoints");
      Map<String, Object> dp = new LinkedHashMap<>();
      dp.put("attributes", attributes(point.attributes()));
      dp.put("timeUnixNano", Long.toString(nowNanos));
      dp.put("asDouble", point.value());
      dataPoints.add(dp);
    }
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("scope", Map.of("name", "io.github.chelin1974.cellgatehome", "version", version));
    scope.put("metrics", new ArrayList<>(metrics.values()));
    Map<String, Object> resourceMetrics = new LinkedHashMap<>();
    resourceMetrics.put("resource", Map.of("attributes", attributes(resource)));
    resourceMetrics.put("scopeMetrics", List.of(scope));
    return Map.of("resourceMetrics", List.of(resourceMetrics));
  }

  private static List<Object> attributes(Map<String, String> values) {
    List<Object> out = new ArrayList<>();
    values.forEach((key, value) -> out.add(Map.of("key", key, "value", Map.of("stringValue", value))));
    return out;
  }
}

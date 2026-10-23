package io.github.chelin1974.cellgatehome.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chelin1974.cellgatehome.metrics.CellGateMetrics.Point;
import java.util.List;
import java.util.function.Supplier;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes the readings to an OTLP/HTTP metrics endpoint on a fixed interval.
 *
 * <p>Uses the JSON encoding, which Prometheus' OTLP receiver accepts as {@code application/json},
 * so no OpenTelemetry SDK is needed. A backend that is down never affects the bridge: the push
 * is skipped, the first failure is logged, then only one reminder every ten minutes until it
 * recovers.
 */
public final class OtlpExporter implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(OtlpExporter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration QUIET = Duration.ofMinutes(10);

  private final URI endpoint;
  private final Supplier<List<Point>> source;
  private final Duration interval;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final Map<String, String> resource = new LinkedHashMap<>();

  /** Its own thread, so a slow or unreachable backend never holds up HomeKit. */
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cellgate-home-metrics");
        t.setDaemon(true);
        return t;
      });

  private ScheduledFuture<?> task;
  private volatile boolean failing;
  private volatile Instant lastComplaint = Instant.EPOCH;

  public OtlpExporter(URI endpoint, Supplier<List<Point>> source, Duration interval, String version) {
    this.endpoint = endpoint;
    this.source = source;
    this.interval = interval;
    resource.put("service.name", "cellgate-home");
    resource.put("service.version", version);
    resource.put("host.name", hostName());
  }

  public URI endpoint() {
    return endpoint;
  }

  public void start() {
    task = scheduler.scheduleWithFixedDelay(this::pushQuietly, 5, interval.toSeconds(), TimeUnit.SECONDS);
    log.info("sending metrics to {} every {}s", endpoint, interval.toSeconds());
  }

  private void pushQuietly() {
    try {
      push();
    } catch (RuntimeException e) {
      complain("could not build the metrics request: " + e.getMessage());
    }
  }

  /** Sends one request now. Returns true when the backend accepted it. */
  public boolean push() {
    var points = source.get();
    if (points.isEmpty()) {
      return false;
    }
    String body;
    try {
      body = MAPPER.writeValueAsString(CellGateMetrics.request(points, nanos(Instant.now()), resource, resource.get("service.version")));
    } catch (Exception e) {
      complain("could not encode metrics: " + e.getMessage());
      return false;
    }
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 == 2) {
        if (failing) {
          log.info("metrics endpoint {} is accepting data again", endpoint);
          failing = false;
        }
        return true;
      }
      String hint = response.statusCode() == 404
          ? " Is Prometheus running with --web.enable-otlp-receiver?"
          : "";
      complain("metrics endpoint answered HTTP " + response.statusCode() + ": " + truncate(response.body()) + "." + hint);
    } catch (java.io.IOException e) {
      complain("could not reach the metrics endpoint " + endpoint + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return false;
  }

  private void complain(String message) {
    Instant now = Instant.now();
    if (!failing || Duration.between(lastComplaint, now).compareTo(QUIET) >= 0) {
      log.warn("{} Readings are not being recorded until this is fixed; the bridge itself keeps working.", message);
      lastComplaint = now;
    }
    failing = true;
  }

  @Override
  public void close() {
    if (task != null) {
      task.cancel(false);
    }
    scheduler.shutdownNow();
  }

  static long nanos(Instant instant) {
    return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }

  private static String truncate(String value) {
    String v = value == null ? "" : value.strip();
    return v.length() > 200 ? v.substring(0, 200) + "..." : v;
  }
}

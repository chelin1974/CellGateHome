package io.github.chelin1974.cellgatehome;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the bridge needs, read from {@code cellgate.config.json}, validated and clamped.
 *
 * <pre>{@code
 * {
 *   "email": "you@example.com",
 *   "password": "...",
 *   "bridgeName": "CellGate",
 *   "dashboardPort": 8090,
 *   "dashboardPasscode": "...",
 *   "prometheusUrl": "http://localhost:9090"
 * }
 * }</pre>
 *
 * <p>Credentials and anything identifying stay in this file; it is in {@code .gitignore}. Keep it
 * owner-only ({@code chmod 600}), because it signs in to the CellGate account.
 */
public final class CellGateConfig {

  public static final String DEFAULT_BRIDGE_NAME = "CellGate";
  public static final int DEFAULT_PORT = 51829;
  public static final int DEFAULT_DASHBOARD_PORT = 8090;
  public static final String DEFAULT_STATE_FILE = "cellgate-home.state.json";

  /** How often the bridge re-reads the gate state from CellGate. */
  public static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(20);
  public static final Duration DEFAULT_METRICS_INTERVAL = Duration.ofSeconds(60);
  public static final Duration MIN_METRICS_INTERVAL = Duration.ofSeconds(10);
  public static final String PROMETHEUS_OTLP_PATH = "/api/v1/otlp/v1/metrics";

  public static final class ConfigException extends RuntimeException {
    public ConfigException(String message) {
      super(message);
    }

    public ConfigException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final String email;
  private final String password;
  private final Duration pollInterval;
  private final Duration requestTimeout;
  private final String bridgeName;
  private final int port;
  private final int dashboardPort;
  private final String dashboardPasscode;
  private final String bindAddress;
  private final Path storagePath;
  private final URI otlpEndpoint;
  private final Duration metricsInterval;
  private final String prometheusUrl;

  private CellGateConfig(Raw raw, Path directory) {
    this.email = trimmed(raw.email);
    this.password = raw.password;
    List<String> missing = new ArrayList<>();
    if (isBlank(email)) {
      missing.add("email");
    }
    if (password == null || password.isEmpty()) {
      missing.add("password");
    }
    if (!missing.isEmpty()) {
      throw new ConfigException(
          "missing required setting" + (missing.size() > 1 ? "s" : "") + ": "
              + String.join(", ", missing) + ". Use the email and password you sign in to the"
              + " CellGate app (or user.zapopen.com) with.");
    }

    this.pollInterval = atLeast(raw.pollIntervalSeconds, 20, MIN_POLL_INTERVAL);
    this.requestTimeout = atLeast(raw.requestTimeoutSeconds, 20, Duration.ofSeconds(5));
    this.bridgeName = isBlank(raw.bridgeName) ? DEFAULT_BRIDGE_NAME : raw.bridgeName.trim();
    this.port = raw.port == null ? DEFAULT_PORT : raw.port;
    this.dashboardPort = raw.dashboardPort == null ? DEFAULT_DASHBOARD_PORT : raw.dashboardPort;
    this.dashboardPasscode = raw.dashboardPasscode == null ? "" : raw.dashboardPasscode;
    this.bindAddress = isBlank(raw.bindAddress) ? null : raw.bindAddress.trim();
    this.prometheusUrl = isBlank(raw.prometheusUrl) ? null : raw.prometheusUrl.trim();
    this.storagePath = isBlank(raw.storagePath)
        ? directory.resolve(DEFAULT_STATE_FILE)
        : Path.of(raw.storagePath.trim());

    if (!isBlank(raw.otlpEndpoint)) {
      this.otlpEndpoint = httpUri("otlpEndpoint", raw.otlpEndpoint.trim());
    } else if (prometheusUrl != null) {
      this.otlpEndpoint = httpUri("prometheusUrl", prometheusUrl.replaceAll("/+$", "") + PROMETHEUS_OTLP_PATH);
    } else {
      this.otlpEndpoint = null;
    }
    this.metricsInterval = raw.metricsIntervalSeconds == null
        ? DEFAULT_METRICS_INTERVAL
        : Duration.ofSeconds(Math.max(MIN_METRICS_INTERVAL.toSeconds(), raw.metricsIntervalSeconds));
  }

  public String email() {
    return email;
  }

  public String password() {
    return password;
  }

  public Duration pollInterval() {
    return pollInterval;
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }

  public String bridgeName() {
    return bridgeName;
  }

  public int port() {
    return port;
  }

  public int dashboardPort() {
    return dashboardPort;
  }

  public String dashboardPasscode() {
    return dashboardPasscode;
  }

  public String bindAddress() {
    return bindAddress;
  }

  public Path storagePath() {
    return storagePath;
  }

  public URI otlpEndpoint() {
    return otlpEndpoint;
  }

  public Duration metricsInterval() {
    return metricsInterval;
  }

  public java.util.Optional<URI> prometheusUri() {
    return prometheusUrl == null ? java.util.Optional.empty()
        : java.util.Optional.of(httpUri("prometheusUrl", prometheusUrl));
  }

  @Override
  public String toString() {
    return "CellGateConfig[email=" + email + ", bridgeName=" + bridgeName + ", port=" + port
        + ", dashboardPort=" + dashboardPort + ", pollInterval=" + pollInterval
        + ", otlpEndpoint=" + otlpEndpoint + ", storagePath=" + storagePath + "]";
  }

  public static CellGateConfig load(Path file) {
    if (!Files.isReadable(file)) {
      throw new ConfigException(
          "cannot read the configuration file " + file.toAbsolutePath()
              + ". Copy cellgate.config.example.json and fill in your CellGate email and password.");
    }
    Raw raw;
    try {
      raw = new ObjectMapper().readValue(Files.readString(file), Raw.class);
    } catch (IOException e) {
      throw new ConfigException("could not parse " + file.toAbsolutePath() + ": " + e.getMessage(), e);
    }
    Path parent = file.toAbsolutePath().getParent();
    return new CellGateConfig(raw, parent == null ? Path.of(".") : parent);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class Raw {
    public String email;
    public String password;
    public Integer pollIntervalSeconds;
    public Integer requestTimeoutSeconds;
    public String bridgeName;
    public Integer port;
    public Integer dashboardPort;
    public String dashboardPasscode;
    public String bindAddress;
    public String storagePath;
    public String prometheusUrl;
    public String otlpEndpoint;
    public Integer metricsIntervalSeconds;
  }

  private static Duration atLeast(Integer seconds, int fallback, Duration floor) {
    Duration d = Duration.ofSeconds(seconds == null ? fallback : seconds);
    return d.compareTo(floor) < 0 ? floor : d;
  }

  private static URI httpUri(String setting, String value) {
    try {
      URI uri = URI.create(value);
      if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null) {
        return uri;
      }
    } catch (IllegalArgumentException e) {
      // fall through
    }
    throw new ConfigException(setting + " \"" + value + "\" is not an http(s) URL, such as http://localhost:9090.");
  }

  private static String trimmed(String value) {
    return value == null ? null : value.trim();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  @SuppressWarnings("unused")
  private static String lower(String v) {
    return v.toLowerCase(Locale.ROOT);
  }
}

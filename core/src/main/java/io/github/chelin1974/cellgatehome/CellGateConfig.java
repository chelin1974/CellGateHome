package io.github.chelin1974.cellgatehome;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
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

  /** Default for {@code assumeOpenSeconds}; see where it is applied for the reasoning. */
  public static final Duration DEFAULT_ASSUME_OPEN = Duration.ofSeconds(60);
  public static final Duration MIN_METRICS_INTERVAL = Duration.ofSeconds(10);
  public static final String PROMETHEUS_OTLP_PATH = "/api/v1/otlp/v1/metrics";

  /**
   * Which activity-log entries count as "someone is at the gate" and ring the HomeKit doorbell.
   * Matched as case-insensitive substrings of each log entry's description. The default is the
   * camera-snapshot the gate takes whenever someone interacts with it ("Internal Camera Response
   * Picture"); that fires once per visit and, unlike a plain "Gate Opened", does not fire when the
   * owner opens the gate remotely from the app.
   */
  public static final List<String> DEFAULT_DOORBELL_RING_EVENTS = List.of("Response Picture");

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
  private final Path dataDirectory;
  private final Path storagePath;
  private final URI otlpEndpoint;
  private final Duration metricsInterval;
  private final String prometheusUrl;
  private final boolean doorbell;
  private final Duration assumeOpen;
  private final List<String> doorbellRingEvents;

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
    // One rule, shared with the SpanHome and DaikinHome bridges: an absolute storagePath is
    // taken as given, and a relative one is resolved against the configuration file's own
    // directory - never the process working directory, which is whatever happened to launch
    // the bridge and differs between a shell, a systemd unit and a container. Losing track of
    // this file un-pairs every controller, so where it lands must not depend on how the
    // bridge was started.
    // Where everything the bridge writes goes: pairing state, accessory IDs, the layout
    // number. Relative to the configuration file's directory, or absolute; unset it is that
    // directory, which is what this bridge has always done, so nothing moves for an existing
    // setup. Naming it lets a deployment put every written file on its mounted volume with one
    // setting, instead of relying on storagePath's parent being the right place. The SpanHome
    // and DaikinHome bridges take the same setting.
    this.dataDirectory =
        isBlank(raw.dataDirectory)
            ? directory
            : resolveAgainstConfig(directory, raw.dataDirectory, "");
    this.storagePath = resolveAgainstConfig(dataDirectory, raw.storagePath, DEFAULT_STATE_FILE);

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

    // How long to go on reporting the gate open after triggering it, for hardware that
    // reports no position. Unset, a minute: long enough to cover a gate swinging open and
    // standing there, short enough that a stale "open" does not linger all day. 0 turns it
    // off, which is right for a unit whose status sensor does report - there the measured
    // reading wins anyway, so this only ever fills a gap.
    this.assumeOpen = Duration.ofSeconds(
        raw.assumeOpenSeconds == null ? DEFAULT_ASSUME_OPEN.toSeconds()
            : Math.max(0, raw.assumeOpenSeconds));

    this.doorbell = raw.doorbell == null || raw.doorbell;
    List<String> ring = new ArrayList<>();
    if (raw.doorbellRingEvents != null) {
      for (String e : raw.doorbellRingEvents) {
        if (e != null && !e.isBlank()) {
          ring.add(e.trim());
        }
      }
    }
    this.doorbellRingEvents = ring.isEmpty() ? DEFAULT_DOORBELL_RING_EVENTS : List.copyOf(ring);
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

  /**
   * The directory every written file lands in.
   *
   * <p>{@code dataDirectory} when set, and the configuration file's own directory otherwise.
   */
  public Path dataDirectory() {
    return dataDirectory;
  }

  public URI otlpEndpoint() {
    return otlpEndpoint;
  }

  public Duration metricsInterval() {
    return metricsInterval;
  }

  /**
   * How long a gate keeps reporting open after it is triggered, when nothing measures its
   * position. Zero turns the assumption off and such a gate reads Unknown instead.
   */
  public Duration assumeOpen() {
    return assumeOpen;
  }

  /** Whether to publish a HomeKit doorbell that rings on gate activity. */
  public boolean doorbell() {
    return doorbell;
  }

  /** Case-insensitive substrings of a log entry's description that ring the doorbell. */
  public List<String> doorbellRingEvents() {
    return doorbellRingEvents;
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
    return load(file, null);
  }

  /**
   * The configuration, with {@code dataDirectory} forced to {@code dataDirectoryOverride}.
   *
   * <p>For a deployment that must put every written file somewhere specific without editing the
   * user's configuration - a container pointing them at its mounted volume, where the config
   * itself is on a read-only mount and defaulting to its directory would make every write fail.
   * A null or blank override changes nothing.
   */
  public static CellGateConfig load(Path file, String dataDirectoryOverride) {
    if (!Files.isReadable(file)) {
      throw new ConfigException(
          "cannot read the configuration file " + file.toAbsolutePath()
              + ". Copy cellgate.config.example.json and fill in your CellGate email and password.");
    }
    Raw raw;
    try {
      raw = new ObjectMapper().readValue(Files.readString(file), Raw.class);
    } catch (IOException | JacksonException e) {
      throw new ConfigException("could not parse " + file.toAbsolutePath() + ": " + e.getMessage(), e);
    }
    if (!isBlank(dataDirectoryOverride)) {
      raw.dataDirectory = dataDirectoryOverride.strip();
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
    public String dataDirectory;
    public String storagePath;
    public String prometheusUrl;
    public String otlpEndpoint;
    public Integer metricsIntervalSeconds;
    public Boolean doorbell;
    public List<String> doorbellRingEvents;
    public Integer assumeOpenSeconds;
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

  /**
   * A configured path, relative to the configuration file's own directory.
   *
   * <p>Absolute paths are taken as given. A blank setting falls back to {@code fallback} in
   * the same directory. The process working directory is deliberately never involved: it is
   * whatever launched the bridge, and differs between a shell, a systemd unit and a container.
   */
  private static Path resolveAgainstConfig(Path directory, String configured, String fallback) {
    Path path = Path.of(isBlank(configured) ? fallback : configured.trim());
    return path.isAbsolute() ? path : directory.resolve(path);
  }

  @SuppressWarnings("unused")
  private static String lower(String v) {
    return v.toLowerCase(Locale.ROOT);
  }
}

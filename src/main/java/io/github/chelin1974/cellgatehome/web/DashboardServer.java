package io.github.chelin1974.cellgatehome.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.chelin1974.cellgatehome.web.DashboardModel.OpenResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Gate Board dashboard: a small web page and JSON API served on the home network only.
 *
 * <pre>
 *   GET  /                          the page
 *   GET  /api/state                 the gates and their open/closed state
 *   GET  /api/activity              recent CellGate activity (openings, codes, pictures)
 *   GET  /api/cameras               the latest picture for each camera
 *   GET  /api/image?name=...        the bytes of one stored picture
 *   POST /api/gates/{id}/open       open a gate, with header X-Passcode
 *   POST /api/pictures              take a fresh picture, with header X-Passcode
 * </pre>
 *
 * <p>Opening a gate and taking a picture need the household passcode ({@code dashboardPasscode});
 * with none set the page is view-only. Five wrong passcodes from one address lock it out for five
 * minutes. The server answers only private, link-local and loopback addresses.
 */
public final class DashboardServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DashboardServer.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern OPEN = Pattern.compile("^/api/gates/([A-Za-z0-9._:-]{1,128})/open$");

  static final int MAX_FAILURES = 5;
  static final Duration LOCKOUT = Duration.ofMinutes(5);
  static final int ACTIVITY_COUNT = 20;

  private final DashboardModel model;
  private final byte[] passcode;
  private final HttpServer server;
  private final ExecutorService executor;
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();

  static final class Failures {
    int count;
    Instant lockedUntil = Instant.EPOCH;
  }

  /** @param passcode null or blank for a view-only page. */
  public DashboardServer(int port, DashboardModel model, String passcode) throws IOException {
    this.model = model;
    this.passcode = passcode == null || passcode.isBlank() ? null : passcode.getBytes(StandardCharsets.UTF_8);
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    this.executor = Executors.newFixedThreadPool(4, r -> {
      Thread t = new Thread(r, "cellgate-home-web");
      t.setDaemon(true);
      return t;
    });
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public boolean switchingEnabled() {
    return passcode != null;
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      InetAddress remote = exchange.getRemoteAddress().getAddress();
      if (!isLocal(remote)) {
        send(exchange, 403, Map.of("error", "This dashboard only answers devices on the home network."));
        return;
      }
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();

      if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
        page(exchange);
      } else if (method.equals("GET") && path.equals("/api/state")) {
        send(exchange, 200, model.state(switchingEnabled()));
      } else if (method.equals("GET") && path.equals("/api/activity")) {
        proxy(exchange, () -> Map.of("entries", model.activity(ACTIVITY_COUNT)));
      } else if (method.equals("GET") && path.equals("/api/cameras")) {
        proxy(exchange, () -> Map.of("cameras", model.cameras()));
      } else if (method.equals("GET") && path.equals("/api/image")) {
        image(exchange);
      } else if (method.equals("POST") && path.equals("/api/pictures")) {
        takePicture(exchange, remote);
      } else {
        Matcher open = OPEN.matcher(path);
        if (open.matches() && method.equals("POST")) {
          open(exchange, open.group(1), remote);
        } else {
          send(exchange, 404, Map.of("error", "Not found"));
        }
      }
    } catch (RuntimeException e) {
      log.warn("dashboard request failed: {}", e.getMessage());
    }
  }

  private void page(HttpExchange exchange) throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/dashboard/index.html")) {
      if (in == null) {
        send(exchange, 500, Map.of("error", "The dashboard page is missing from the jar."));
        return;
      }
      byte[] bytes = in.readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  /** Runs a CellGate-backed read and sends it as JSON, or an error if CellGate is unreachable. */
  private void proxy(HttpExchange exchange, java.util.function.Supplier<Object> body) throws IOException {
    try {
      send(exchange, 200, body.get());
    } catch (RuntimeException e) {
      send(exchange, 502, Map.of("error", "CellGate didn't answer: " + e.getMessage()));
    }
  }

  private void image(HttpExchange exchange) throws IOException {
    String query = exchange.getRequestURI().getRawQuery();
    String name = query == null ? null : paramValue(query, "name");
    if (name == null || name.isBlank()) {
      send(exchange, 400, Map.of("error", "Pass ?name=<attachment>."));
      return;
    }
    Optional<byte[]> bytes;
    try {
      bytes = model.image(name);
    } catch (RuntimeException e) {
      send(exchange, 502, Map.of("error", "CellGate didn't answer: " + e.getMessage()));
      return;
    }
    if (bytes.isEmpty()) {
      send(exchange, 404, Map.of("error", "No such image."));
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
    exchange.getResponseHeaders().set("Cache-Control", "max-age=86400");
    exchange.sendResponseHeaders(200, bytes.get().length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes.get());
    }
  }

  private void open(HttpExchange exchange, String gateId, InetAddress remote) throws IOException {
    if (!authorize(exchange, remote)) {
      return;
    }
    OpenResult result = model.open(gateId);
    switch (result) {
      case OK -> {
        log.info("dashboard: opened gate {}", gateId);
        send(exchange, 200, Map.of("ok", true));
      }
      case NOT_FOUND -> send(exchange, 404, Map.of("error", "No such gate."));
      case FAILED -> send(exchange, 502, Map.of("error", "CellGate did not take the command."));
    }
  }

  private void takePicture(HttpExchange exchange, InetAddress remote) throws IOException {
    if (!authorize(exchange, remote)) {
      return;
    }
    try {
      model.takePictures();
      send(exchange, 200, Map.of("ok", true));
    } catch (RuntimeException e) {
      send(exchange, 502, Map.of("error", "CellGate did not take the command: " + e.getMessage()));
    }
  }

  /** Checks switching is on, the device isn't locked out, and the passcode matches. */
  private boolean authorize(HttpExchange exchange, InetAddress remote) throws IOException {
    if (passcode == null) {
      send(exchange, 403, Map.of("error",
          "Opening the gate from the dashboard is off. Set dashboardPasscode in cellgate.config.json."));
      return false;
    }
    String key = remote.getHostAddress();
    Failures f = failures.computeIfAbsent(key, k -> new Failures());
    synchronized (f) {
      if (Instant.now().isBefore(f.lockedUntil)) {
        send(exchange, 429, Map.of("error", "Too many wrong passcodes. Try again in a few minutes."));
        return false;
      }
    }
    String given = exchange.getRequestHeaders().getFirst("X-Passcode");
    boolean ok = given != null
        && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), passcode);
    if (!ok) {
      synchronized (f) {
        if (++f.count >= MAX_FAILURES) {
          f.lockedUntil = Instant.now().plus(LOCKOUT);
          f.count = 0;
        }
      }
      send(exchange, 401, Map.of("error", "Wrong passcode."));
      return false;
    }
    synchronized (f) {
      f.count = 0;
    }
    return true;
  }

  /** Private IPv4, IPv6 unique-local, link-local and loopback. */
  public static boolean isLocal(InetAddress address) {
    if (address == null) {
      return false;
    }
    if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
      return true;
    }
    if (address instanceof Inet6Address) {
      return (address.getAddress()[0] & 0xfe) == 0xfc; // fc00::/7
    }
    return false;
  }

  public static String paramValue(String query, String key) {
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String k = eq < 0 ? pair : pair.substring(0, eq);
      if (k.equals(key)) {
        String v = eq < 0 ? "" : pair.substring(eq + 1);
        return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static void send(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = MAPPER.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}

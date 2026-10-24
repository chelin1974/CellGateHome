package io.github.chelin1974.cellgatehome.cellgate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and controls a CellGate account the same way the TrueCloud Connect portal
 * (user.zapopen.com) does, on behalf of the account's owner.
 *
 * <p>It signs in with the account email and password, holds the returned session, and signs in
 * again when the session expires. Request bodies and query strings are encrypted with
 * {@link CellGateCrypto}; replies come back as plain JSON. Calls are made one at a time.
 *
 * <p>CellGate does not publish this interface, so endpoints and payloads may change; the README
 * says more. The bridge only reads the account's own devices and triggers the owner's own gate
 * and camera.
 */
public final class CellGateClient {

  private static final Logger log = LoggerFactory.getLogger(CellGateClient.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Service hosts, as named in the portal's configuration. */
  static final String ACCOUNTKIT = "https://accountkit-user.zapopen.com";
  static final String DEVICE_ACTION = "https://deviceaction.zapopen.com";
  static final String LOG = "https://log.zapopen.com";

  /**
   * A fixed client key the portal attaches to every request as the {@code is_e} header (its config
   * calls it {@code Is_E}). The services reject a request without it as unauthenticated — which is
   * why a sign-in with the right password still came back empty until this was sent.
   */
  static final String IS_E =
      "R3rcMGRoRsCuiU5TkzxTrUvPzihGQBr5h96aApNMALcRKxmjZ2Sg1MsH9gZva7zKA8n5p9qN0kSj4488LYW4X2FZDkCx4f8wbYGn8G7Ps69Cyd5vhQgtdL2wqBO4KW53tmXKlEb0Pjh/MY2gRcxB87KuTBWpxIeAo7SkOMMRAcE=";

  /** The portal re-logs in after about an hour; refresh a little sooner. */
  static final Duration SESSION_LIFE = Duration.ofMinutes(50);

  /** Thrown when CellGate can't be reached or rejects the request. */
  public static class CellGateException extends RuntimeException {
    public CellGateException(String message) {
      super(message);
    }

    public CellGateException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final String email;
  private final String password;
  private final CellGateCrypto crypto;
  private final HttpClient http;
  private final Duration timeout;

  private volatile Session session;

  /** One sign-in: the token, session key and CSRF token the portal sends on later requests. */
  record Session(String token, String key, String csrf, long userId, long accountId,
      String username, Instant since) {}

  public CellGateClient(String email, String password, Duration requestTimeout) {
    this(email, password, CellGateCrypto.portal(),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), requestTimeout);
  }

  CellGateClient(String email, String password, CellGateCrypto crypto, HttpClient http, Duration requestTimeout) {
    this.email = email;
    this.password = password;
    this.crypto = crypto;
    this.http = http;
    this.timeout = requestTimeout;
  }

  /** Signs in and returns who the account belongs to. Throws on a bad email or password. */
  public synchronized Session signIn() {
    String json = MAPPER.createObjectNode().put("username", email).put("password", password).toString();
    // Login is the one call sent without a session key (there isn't one yet).
    JsonNode reply = postJson(ACCOUNTKIT + "/api/AccountKit/userlogin", crypto.body(json, null), null);

    // The portal has wrapped the login reply differently across versions, so look for the pieces
    // in the likely places rather than one fixed path: the body itself, or a "data"/"Data" envelope.
    JsonNode envelope = firstObject(reply, "data", "Data");
    JsonNode user = firstObject(reply, "user", "User");
    if (user == null && envelope != null) {
      user = firstObject(envelope, "user", "User");
    }
    if (user == null && hasAny(reply, "SessionID", "ID", "AccountID")) {
      user = reply; // the reply *is* the user object
    }
    if (user == null && envelope != null && hasAny(envelope, "SessionID", "ID", "AccountID")) {
      user = envelope;
    }

    String token = firstText(reply, "AuthToken", "Token", "token", "access_token");
    if (token == null && envelope != null) {
      token = firstText(envelope, "AuthToken", "Token", "token", "access_token");
    }
    if (token == null && user != null) {
      token = firstText(user, "AuthToken", "Token", "token", "access_token");
    }

    if (user == null || token == null) {
      // Came back, but not in a shape we recognise. Log the structure — field names and types
      // only, never values or tokens — so the parsing can be matched to the real reply.
      log.error("unexpected sign-in response; its structure was: {}", describe(reply));
      throw new CellGateException("the CellGate sign-in was refused; check the email and password.");
    }

    String key = firstText(user, "SessionID", "SessionId", "sessionId");
    String csrf = firstText(reply, "CsrfToken", "csrfToken", "csrf");
    session = new Session(
        token,
        key == null ? "" : key,
        csrf == null ? "" : csrf,
        firstLong(user, "ID", "Id", "UserID", "UserId"),
        firstLong(user, "AccountID", "AccountId"),
        orElse(firstText(user, "Username", "username"), email),
        Instant.now());
    log.info("signed in to CellGate as {}", session.username());
    return session;
  }

  private static JsonNode firstObject(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode v = node.get(key);
      if (v != null && v.isObject()) {
        return v;
      }
    }
    return null;
  }

  private static boolean hasAny(JsonNode node, String... keys) {
    for (String key : keys) {
      if (node.hasNonNull(key)) {
        return true;
      }
    }
    return false;
  }

  private static String firstText(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode v = node.get(key);
      if (v != null && v.isValueNode() && !v.isNull()) {
        String s = v.asText(null);
        if (s != null && !s.isBlank()) {
          return s;
        }
      }
    }
    return null;
  }

  private static long firstLong(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode v = node.get(key);
      if (v != null && !v.isNull()) {
        return v.asLong();
      }
    }
    return 0L;
  }

  private static String orElse(String value, String fallback) {
    return value == null ? fallback : value;
  }

  private Session session() {
    Session s = session;
    if (s == null || Duration.between(s.since(), Instant.now()).compareTo(SESSION_LIFE) >= 0) {
      return signIn();
    }
    return s;
  }

  /** Every Watchman unit on the account, with its ports. */
  public List<Watchman> devices() {
    Session s = session();
    JsonNode modules = get(ACCOUNTKIT + "/api/Dashboard/getModules?UserId=" + s.userId() + "&AccountID=0");
    return Watchman.parse(modules);
  }

  /** Opens (triggers) a gate. The unit decides how long it stays open. */
  public void triggerGate(Watchman unit, Watchman.Port gate) {
    actuate(DEVICE_ACTION + "/api/EVOhttp/EVOWTMMomentaryActuationRequest", unit, gate);
    log.info("triggered gate \"{}\"", gate.name());
  }

  /** Asks the camera to take a fresh picture; it lands in the activity log a moment later. */
  public void takePicture(Watchman unit, Watchman.Port camera) {
    actuate(DEVICE_ACTION + "/api/EVOhttp/EVOWTMtakePicture", unit, camera);
    log.info("asked \"{}\" to take a picture", camera.name());
  }

  private void actuate(String url, Watchman unit, Watchman.Port port) {
    Session s = session();
    String json = MAPPER.createObjectNode()
        .put("ModuleModemId", unit.modemId())
        .put("DeviceSerialNumber", unit.serial())
        .put("ModuleId", unit.id())
        .put("ModulePortId", port.id())
        .put("ModuleType", port.entryType())
        .put("PortNumber", port.number())
        .put("UserID", s.userId())
        .put("IsFromAppTrigger", true)
        .toString();
    postJson(url, crypto.body(json, s.key()), s);
  }

  /** The most recent activity entries (openings, closings, codes, pictures) for the account. */
  public JsonNode activity(int count) {
    Session s = session();
    String json = MAPPER.createObjectNode()
        .put("page", 1)
        .put("count", count)
        .put("accountId", s.accountId())
        .put("username", s.username())
        .set("sort", MAPPER.createArrayNode().add("CreatedOn DESC"))
        .toString();
    return postJson(LOG + "/api/Log/GetLogs", crypto.body(json, s.key()), s).path("data");
  }

  /** The latest picture on each camera port, keyed by port ID, as the dashboard shows them. */
  public JsonNode cameraImages(List<Long> cameraPortIds, Integer timezoneId) {
    Session s = session();
    var ids = MAPPER.createArrayNode();
    cameraPortIds.forEach(ids::add);
    String json = MAPPER.createObjectNode()
        .put("TimezoneID", timezoneId == null ? 0 : timezoneId)
        .set("ModulePortID", ids)
        .toString();
    return postJson(ACCOUNTKIT + "/api/Dashboard/GetPortsImage", crypto.body(json, s.key()), s);
  }

  /** Fetches a stored image's bytes by its attachment name, or empty if it isn't there. */
  public Optional<byte[]> image(String attachment) {
    Session s = session();
    // Pass the raw query; encryptedUrl() encrypts the whole thing, so no pre-encoding here.
    String url = LOG + "/api/Portal/LogImageUpload?filename=" + attachment;
    try {
      HttpResponse<byte[]> response = http.send(authGet(url, s).build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() == 404) {
        return Optional.empty();
      }
      if (response.statusCode() / 100 != 2) {
        throw new CellGateException("CellGate answered HTTP " + response.statusCode() + " for an image");
      }
      return Optional.of(response.body());
    } catch (IOException e) {
      throw new CellGateException("could not reach CellGate for an image: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CellGateException("interrupted fetching an image", e);
    }
  }

  // --------------------------------------------------------------------- HTTP

  private JsonNode get(String url) {
    Session s = session();
    try {
      HttpResponse<String> response = http.send(
          authGet(url, s).build(), HttpResponse.BodyHandlers.ofString());
      return read(url, response.statusCode(), response.body());
    } catch (IOException e) {
      throw new CellGateException("could not reach CellGate: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CellGateException("interrupted", e);
    }
  }

  private JsonNode postJson(String url, String body, Session s) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("is_e", IS_E)
        .header("responsetype", "blob")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    request.header("AuthToken", s == null ? "Bearer null" : "Bearer " + s.token());
    request.header("AuthKey", s == null ? "null" : s.key());
    if (s != null && s.csrf() != null && !s.csrf().isEmpty()) {
      request.header("x-csrf-token", s.csrf());
    }
    try {
      HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return read(url, response.statusCode(), response.body());
    } catch (IOException e) {
      throw new CellGateException("could not reach CellGate: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CellGateException("interrupted", e);
    }
  }

  private HttpRequest.Builder authGet(String url, Session s) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(encryptedUrl(url, s)))
        .timeout(timeout)
        .header("Accept", "application/json")
        .header("is_e", IS_E)
        .header("responsetype", "blob")
        .header("AuthToken", "Bearer " + s.token())
        .header("AuthKey", s.key());
    if (s.csrf() != null && !s.csrf().isEmpty()) {
      request.header("x-csrf-token", s.csrf());
    }
    return request.GET();
  }

  /**
   * The portal does not send GET parameters in the clear: it encrypts the whole query string into a
   * {@code request=…} value and appends the encrypted session key as {@code value=…}. A plain
   * {@code ?UserId=…} is simply ignored by the server, which is why a signed-in account looked
   * empty. Rewrite {@code path?query} the same way; a query-less URL is left untouched.
   */
  private String encryptedUrl(String url, Session s) {
    int q = url.indexOf('?');
    if (q < 0) {
      return url;
    }
    return url.substring(0, q) + "?" + crypto.query(url.substring(q + 1), s.key());
  }

  private JsonNode read(String url, int status, String body) {
    if (status == 401) {
      session = null;
      throw new CellGateException("CellGate ended the session (HTTP 401)");
    }
    if (status / 100 != 2) {
      throw new CellGateException("CellGate answered HTTP " + status + " for " + path(url));
    }
    try {
      return body == null || body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    } catch (IOException e) {
      throw new CellGateException("could not read CellGate's reply from " + path(url), e);
    }
  }

  private static String path(String url) {
    int i = url.indexOf("/api/");
    return i < 0 ? url : url.substring(i);
  }

  /** A compact "field:type" sketch of a JSON node, one level deep, with no values or tokens. */
  private static String describe(JsonNode node) {
    if (node == null || node.isNull()) {
      return "(empty)";
    }
    if (node.isArray()) {
      return "[array of " + node.size() + (node.size() > 0 ? ", first=" + describe(node.get(0)) : "") + "]";
    }
    if (!node.isObject()) {
      return node.getNodeType().toString().toLowerCase(java.util.Locale.ROOT);
    }
    StringBuilder sb = new StringBuilder("{");
    var fields = node.fields();
    boolean first = true;
    while (fields.hasNext()) {
      var e = fields.next();
      if (!first) {
        sb.append(", ");
      }
      first = false;
      JsonNode v = e.getValue();
      sb.append(e.getKey()).append(':');
      if (v.isObject()) {
        sb.append('{');
        var inner = v.fieldNames();
        boolean f2 = true;
        while (inner.hasNext()) {
          if (!f2) {
            sb.append(',');
          }
          f2 = false;
          sb.append(inner.next());
        }
        sb.append('}');
      } else if (v.isArray()) {
        sb.append("[array]");
      } else {
        sb.append(v.getNodeType().toString().toLowerCase(java.util.Locale.ROOT));
      }
    }
    return sb.append('}').toString();
  }
}

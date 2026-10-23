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

  /** One sign-in: the token and key the portal sends on every later request, plus the user. */
  record Session(String token, String key, long userId, long accountId, String username, Instant since) {}

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
    JsonNode user = reply.path("user");
    if (!user.isObject() || !reply.hasNonNull("AuthToken")) {
      throw new CellGateException("the CellGate sign-in was refused; check the email and password.");
    }
    session = new Session(
        reply.get("AuthToken").asText(),
        user.path("SessionID").asText(),
        user.path("ID").asLong(),
        user.path("AccountID").asLong(),
        user.path("Username").asText(email),
        Instant.now());
    log.info("signed in to CellGate as {}", session.username());
    return session;
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
    String url = LOG + "/api/Portal/LogImageUpload?filename="
        + java.net.URLEncoder.encode(attachment, StandardCharsets.UTF_8);
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
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (s != null) {
      request.header("AuthToken", "Bearer " + s.token()).header("AuthKey", s.key());
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
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(timeout)
        .header("Accept", "application/json")
        .header("AuthToken", "Bearer " + s.token())
        .header("AuthKey", s.key())
        .GET();
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
}

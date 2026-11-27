package io.github.chelin1974.cellgatehome.cellgate;

import io.github.chelin1974.cellgatehome.cellgate.portal.ApiClient;
import io.github.chelin1974.cellgatehome.cellgate.portal.ApiException;
import io.github.chelin1974.cellgatehome.cellgate.portal.api.ActionsApi;
import io.github.chelin1974.cellgatehome.cellgate.portal.api.ActivityApi;
import io.github.chelin1974.cellgatehome.cellgate.portal.api.DevicesApi;
import io.github.chelin1974.cellgatehome.cellgate.portal.api.SessionApi;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.ActuationPlaintext;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.EncryptedBody;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.GetLogsPlaintext;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.LogEntry;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.LogFilter;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.LoginPlaintext;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.LoginResponse;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.PortImage;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.PortsImagePlaintext;
import io.github.chelin1974.cellgatehome.cellgate.portal.model.PropertyLocationId;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads and controls a CellGate account the same way the TrueCloud Connect portal
 * (user.zapopen.com) does, on behalf of the account's owner.
 *
 * <p>The HTTP calls and the JSON are the generated client in {@code cellgate.portal}, built from
 * {@code openapi.yaml}. This class adds what the spec can describe but a generator cannot do:
 *
 * <ul>
 *   <li><b>Encryption.</b> Every operation's real parameters are a plaintext - a record such as
 *       {@link ActuationPlaintext} for a body, a query string for a GET - that the spec names in
 *       {@code x-plaintext-schema} / {@code x-plaintext-query}. Here it is serialised, encrypted
 *       with {@link CellGateCrypto}, and handed to the generated method as the
 *       {@link EncryptedBody} or the {@code request} / {@code value} parameters it expects.
 *   <li><b>The session.</b> Signing in, the {@code is_e} / {@code AuthToken} / {@code AuthKey}
 *       headers on every call, and signing in again after {@link #SESSION_LIFE} or a 401.
 *   <li><b>One host per API.</b> The portal is spread over three hosts, and the generated client
 *       has one base URL per {@link ApiClient}, so each API gets its own.
 * </ul>
 *
 * <p>CellGate does not publish this interface, so endpoints and payloads may change; the README
 * says more. The bridge only reads the account's own devices and triggers the owner's own gate
 * and camera.
 */
public final class CellGateClient {

  private static final Logger log = LoggerFactory.getLogger(CellGateClient.class);

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

  /** The portal sends this on every request, and so does the client. */
  private static final String RESPONSE_TYPE = "blob";

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
  /** Serialises plaintexts; the generated client's own mapper, so names and nulls match it. */
  private final ObjectMapper mapper;

  private final SessionApi sessionApi;
  private final DevicesApi devicesApi;
  private final ActionsApi actionsApi;
  private final ActivityApi activityApi;

  private volatile Session session;
  /** Location IDs for the current session, fetched once per sign-in. */
  private volatile Map.Entry<Session, List<Long>> locationIds;

  /** One sign-in: the token, session key and CSRF token the portal sends on later requests. */
  record Session(String token, String key, String csrf, long userId, long accountId,
      String username, Instant since) {}

  public CellGateClient(String email, String password, Duration requestTimeout) {
    this(email, password, CellGateCrypto.portal(), requestTimeout,
        Map.of(ACCOUNTKIT, ACCOUNTKIT, DEVICE_ACTION, DEVICE_ACTION, LOG, LOG));
  }

  /**
   * @param hosts where each portal host is really reached, keyed by its production URL; tests
   *     point all three at a local server
   */
  CellGateClient(String email, String password, CellGateCrypto crypto, Duration requestTimeout,
      Map<String, String> hosts) {
    this.email = email;
    this.password = password;
    this.crypto = crypto;
    final ApiClient accountKit = apiClient(hosts.get(ACCOUNTKIT), requestTimeout);
    this.mapper = accountKit.getObjectMapper();
    this.sessionApi = new SessionApi(accountKit);
    this.devicesApi = new DevicesApi(accountKit);
    this.actionsApi = new ActionsApi(apiClient(hosts.get(DEVICE_ACTION), requestTimeout));
    this.activityApi = new ActivityApi(apiClient(hosts.get(LOG), requestTimeout));
  }

  private static ApiClient apiClient(String baseUri, Duration requestTimeout) {
    final var uri = URI.create(baseUri);
    final var client = new ApiClient();
    client.setHttpClientBuilder(HttpClient.newBuilder());
    client.setConnectTimeout(Duration.ofSeconds(10));
    client.setReadTimeout(requestTimeout);
    client.setScheme(uri.getScheme());
    client.setHost(uri.getHost());
    client.setPort(uri.getPort());
    client.setBasePath(uri.getPath());
    return client;
  }

  // ------------------------------------------------------------------ session

  /** Signs in and returns who the account belongs to. Throws on a bad email or password. */
  public synchronized Session signIn() {
    final LoginResponse reply;
    try {
      // The one call without a session: no key, so Value is empty, and the portal sends the
      // literal "Bearer null" / "null" for the auth headers.
      reply = sessionApi.userLogin(
          encryptedBody(new LoginPlaintext(email, password), null),
          RESPONSE_TYPE, "Bearer null", "null", Map.of("is_e", IS_E));
    } catch (ApiException e) {
      throw failure("sign-in", e);
    }
    if (reply == null || reply.authToken() == null || reply.user() == null) {
      // Came back, but not in the shape the spec records. Say which part, never the values.
      log.error("unexpected sign-in response: token {}, user {}",
          reply == null || reply.authToken() == null ? "missing" : "present",
          reply == null || reply.user() == null ? "missing" : "present");
      throw new CellGateException("the CellGate sign-in was refused; check the email and password.");
    }
    final var user = reply.user();
    session = new Session(
        reply.authToken(),
        Objects.requireNonNullElse(user.sessionID(), ""),
        Objects.requireNonNullElse(reply.csrfToken(), ""),
        orZero(user.ID()),
        orZero(user.accountID()),
        Objects.requireNonNullElse(user.username(), email),
        Instant.now());
    log.info("signed in to CellGate as {}", session.username());
    return session;
  }

  private Session session() {
    final Session s = session;
    if (s == null || Duration.between(s.since(), Instant.now()).compareTo(SESSION_LIFE) >= 0) {
      return signIn();
    }
    return s;
  }

  // -------------------------------------------------------------- operations

  /** Every Watchman unit on the account, with its ports. */
  public List<Watchman> devices() {
    return call("getModules", s -> Watchman.from(devicesApi.getModules(
        crypto.encrypt("UserId=" + s.userId() + "&AccountID=0"), encryptedKey(s),
        RESPONSE_TYPE, csrf(s), headers(s))));
  }

  /** Opens (triggers) a gate. The unit decides how long it stays open. */
  public void triggerGate(Watchman unit, Watchman.Port gate) {
    call("momentaryActuation", s -> {
      actionsApi.momentaryActuation(
          encryptedBody(actuation(s, unit, gate), s.key()), RESPONSE_TYPE, csrf(s), headers(s));
      return null;
    });
    log.info("triggered gate \"{}\"", gate.name());
  }

  /** Asks the camera to take a fresh picture; it lands in the activity log a moment later. */
  public void takePicture(Watchman unit, Watchman.Port camera) {
    call("takePicture", s -> {
      actionsApi.takePicture(
          encryptedBody(actuation(s, unit, camera), s.key()), RESPONSE_TYPE, csrf(s), headers(s));
      return null;
    });
    log.info("asked \"{}\" to take a picture", camera.name());
  }

  private static ActuationPlaintext actuation(Session s, Watchman unit, Watchman.Port port) {
    return new ActuationPlaintext(unit.modemId(), unit.serial(), unit.id(), port.id(),
        port.entryType(), port.number(), s.userId(), true);
  }

  /**
   * The most recent activity entries (openings, closings, codes, pictures) for the account,
   * newest first; empty, never null.
   *
   * <p>{@code filter} and {@code propertyLocationIDList} look optional and are not: leave either
   * out and the server answers {@code Count: 0} - the same as an account with no activity.
   */
  public List<LogEntry> activity(int count) {
    return call("getLogs", s -> {
      final var plaintext = new GetLogsPlaintext(1, count, s.accountId(), s.username(),
          List.of("CreatedOn DESC"), new LogFilter(null, null, null, null, null, null, null,
              null, null, null, null, null, null, null),
          propertyLocationIds(s), null, null);
      final var reply = activityApi.getLogs(
          encryptedBody(plaintext, s.key()), RESPONSE_TYPE, csrf(s), headers(s));
      return reply == null || reply.data() == null ? List.<LogEntry>of() : reply.data();
    });
  }

  /** The property locations the user can see, which the activity log is filtered by. */
  private List<Long> propertyLocationIds(Session s) throws ApiException {
    final var cached = locationIds;
    if (cached != null && cached.getKey() == s) {
      return cached.getValue();
    }
    List<PropertyLocationId> reply = devicesApi.getPropertyLocationIds(
        crypto.encrypt("AccountID=0&UserID=" + s.userId()), Map.of(), encryptedKey(s),
        RESPONSE_TYPE, csrf(s), headers(s));
    final List<Long> ids = (reply == null ? List.<PropertyLocationId>of() : reply).stream()
        .map(PropertyLocationId::ID)
        .filter(id -> id != null && id != 0)
        .toList();
    locationIds = Map.entry(s, ids);
    return ids;
  }

  /** The latest picture on each camera port, as the dashboard shows them; empty, never null. */
  public List<PortImage> cameraImages(List<Long> cameraPortIds, Integer timezoneId) {
    return call("getPortsImage", s -> {
      final var plaintext = new PortsImagePlaintext(timezoneId == null ? 0 : timezoneId, cameraPortIds);
      final var reply = devicesApi.getPortsImage(
          encryptedBody(plaintext, s.key()), RESPONSE_TYPE, csrf(s), headers(s));
      return reply == null ? List.<PortImage>of() : reply;
    });
  }

  /** Fetches a stored image's bytes by its attachment name, or empty if it isn't there. */
  public Optional<byte[]> image(String attachment) {
    try {
      return call("getLogImage", s -> {
        // The raw name goes into the plaintext query; the whole query is encrypted, so no
        // escaping here.
        final File file = activityApi.getLogImage(crypto.encrypt("filename=" + attachment),
            encryptedKey(s), RESPONSE_TYPE, csrf(s), headers(s));
        try {
          return Optional.of(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
          throw new ApiException(e);
        } finally {
          // The generated client downloads to a temporary file; don't leave it behind.
          if (file != null && !file.delete()) {
            file.deleteOnExit();
          }
        }
      });
    } catch (NotFound e) {
      return Optional.empty();
    }
  }

  // --------------------------------------------------------------- plumbing

  @FunctionalInterface
  private interface Call<T> {
    T run(Session s) throws ApiException;
  }

  /** A 404, kept apart from other failures so {@link #image} can answer "not there". */
  private static final class NotFound extends CellGateException {
    NotFound(String message) {
      super(message);
    }
  }

  private <T> T call(String operation, Call<T> call) {
    try {
      return call.run(session());
    } catch (ApiException e) {
      throw failure(operation, e);
    }
  }

  private CellGateException failure(String operation, ApiException e) {
    final int code = e.getCode();
    if (code == 401) {
      session = null;
      return new CellGateException("CellGate ended the session (HTTP 401)");
    }
    if (code == 404) {
      return new NotFound("CellGate answered HTTP 404 for " + operation);
    }
    if (code != 0) {
      return new CellGateException("CellGate answered HTTP " + code + " for " + operation);
    }
    // Code 0: the generated client wraps transport and parsing failures with no status.
    final Throwable cause = e.getCause() == null ? e : e.getCause();
    if (cause instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      return new CellGateException("interrupted", cause);
    }
    if (cause instanceof JacksonException) {
      return new CellGateException("could not read CellGate's reply to " + operation, cause);
    }
    return new CellGateException("could not reach CellGate: " + cause.getMessage(), cause);
  }

  /**
   * A plaintext record as the portal's {@code {"Data":…,"Value":…}} body: its JSON encrypted,
   * and the session key encrypted ({@code ""} before sign-in).
   */
  private EncryptedBody encryptedBody(Object plaintext, String sessionKey) {
    final String json;
    try {
      json = plaintextMapper().writeValueAsString(plaintext);
    } catch (JacksonException e) {
      throw new CellGateException("could not encode a CellGate request", e);
    }
    return new EncryptedBody(crypto.encrypt(json),
        sessionKey == null || sessionKey.isEmpty() ? "" : crypto.encrypt(sessionKey));
  }

  /**
   * The generated client's mapper, except that it keeps nulls.
   *
   * <p>The generated mapper leaves null properties out, which is right for its own bodies but
   * wrong for a plaintext: the portal sends {@code filter}'s fields and {@code isGlobal} as
   * explicit nulls, and that is the shape the server was checked against.
   */
  private ObjectMapper plaintextMapper() {
    ObjectMapper m = plaintextMapper;
    if (m == null) {
      m = mapper.rebuild()
          .changeDefaultPropertyInclusion(v -> v.withValueInclusion(
              com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS))
          .build();
      plaintextMapper = m;
    }
    return m;
  }

  private volatile ObjectMapper plaintextMapper;

  /** The session key, encrypted for a {@code value} query parameter; null when there is none. */
  private String encryptedKey(Session s) {
    return s.key().isEmpty() ? null : crypto.encrypt(s.key());
  }

  private static String csrf(Session s) {
    return s.csrf().isEmpty() ? null : s.csrf();
  }

  private static Map<String, String> headers(Session s) {
    return Map.of("is_e", IS_E, "AuthToken", "Bearer " + s.token(), "AuthKey", s.key());
  }

  private static long orZero(Long v) {
    return v == null ? 0L : v;
  }
}

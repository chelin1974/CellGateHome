package io.github.chelin1974.cellgatehome.cellgate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.chelin1974.cellgatehome.cellgate.CellGateClient.CellGateException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The encryption and session layer {@link CellGateClient} puts over the generated client, checked
 * against a local server that decrypts what it is sent with the matching private key.
 */
class CellGateClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String LOGIN_REPLY = """
      {"AuthToken":"tok-1","AppToken":"app","user":{"ID":7,"SessionID":"key-1",
       "Username":"owner@example.com","AccountID":12147,"Name":"Owner"}}""";
  private static final String MODULES_REPLY = """
      [{"ID":64277,"ModuleModemID":15841,"DeviceSerialNumber":"0C:11","IsOnline":true,
        "KitHardwareType":{"Model":"WTM-EVO"},"PropertyLocation":{"Name":"Front gate"},
        "ModulePorts":[{"ID":78847,"Name":"Watchman Gate","PortNumber":"01",
          "EntryType":{"Name":"Gate"},"PortType":{"Code":"O"}}]}]""";

  private KeyPair keys;
  private HttpServer server;
  private CellGateClient client;
  /** What each path last received, decrypted. */
  private final Map<String, Received> received = new HashMap<>();
  /** Canned replies by path: status and body. */
  private final Map<String, Object[]> replies = new HashMap<>();
  private final AtomicInteger logins = new AtomicInteger();

  record Received(String method, Map<String, String> query, JsonNode body, String rawBody,
      Map<String, String> headers) {}

  @BeforeEach
  void start() throws Exception {
    final var gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(1024);
    keys = gen.generateKeyPair();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    final var local = "http://127.0.0.1:" + server.getAddress().getPort();
    client = new CellGateClient("owner@example.com", "pw", new CellGateCrypto(keys.getPublic()),
        Duration.ofSeconds(5), Map.of(
            CellGateClient.ACCOUNTKIT, local,
            CellGateClient.DEVICE_ACTION, local,
            CellGateClient.LOG, local));

    replies.put("/api/AccountKit/userlogin", new Object[] {200, LOGIN_REPLY});
    replies.put("/api/Dashboard/getModules", new Object[] {200, MODULES_REPLY});
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    final String path = exchange.getRequestURI().getPath();
    final var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    final var query = new HashMap<String, String>();
    final String q = exchange.getRequestURI().getRawQuery();
    if (q != null) {
      for (final var pair : q.split("&")) {
        final int eq = pair.indexOf('=');
        query.put(pair.substring(0, eq),
            decrypt(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)));
      }
    }
    final JsonNode body = raw.isEmpty() ? null : MAPPER.readTree(raw);
    final var headers = new HashMap<String, String>();
    exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
    received.put(path, new Received(exchange.getRequestMethod(), query, body, raw, headers));
    if (path.endsWith("/userlogin")) {
      logins.incrementAndGet();
    }

    final Object[] reply = replies.getOrDefault(path, new Object[] {200, ""});
    final byte[] bytes = reply[1] instanceof byte[] b ? b : ((String) reply[1]).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type",
        reply[1] instanceof byte[] ? "application/octet-stream" : "application/json");
    exchange.sendResponseHeaders((int) reply[0], bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  private String decrypt(String blob) {
    try {
      final PrivateKey key = keys.getPrivate();
      final var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      final var out = new StringBuilder();
      for (final var piece : blob.split("check")) {
        if (!piece.isEmpty()) {
          cipher.init(Cipher.DECRYPT_MODE, key);
          out.append(new String(cipher.doFinal(Base64.getDecoder().decode(piece)), StandardCharsets.UTF_8));
        }
      }
      return out.toString();
    } catch (Exception e) {
      throw new AssertionError("not encrypted with the expected key: " + blob, e);
    }
  }

  private JsonNode decryptedData(String path) {
    return MAPPER.readTree(decrypt(received.get(path).body().path("Data").asString()));
  }

  @Test
  void signInSendsTheCredentialsEncryptedWithAnEmptyValue() {
    final var session = client.signIn();

    final Received login = received.get("/api/AccountKit/userlogin");
    final JsonNode data = decryptedData("/api/AccountKit/userlogin");
    assertEquals("owner@example.com", data.path("username").asString());
    assertEquals("pw", data.path("password").asString());
    assertEquals("", login.body().path("Value").asString());
    assertEquals(CellGateClient.IS_E, login.headers().get("is_e"));
    assertEquals("Bearer null", login.headers().get("authtoken"));
    assertEquals("null", login.headers().get("authkey"));

    assertEquals("tok-1", session.token());
    assertEquals("key-1", session.key());
    assertEquals(7, session.userId());
    assertEquals(12147, session.accountId());
  }

  @Test
  void aGetHasItsWholeQueryEncryptedAndCarriesTheSession() {
    final List<Watchman> units = client.devices();

    final Received get = received.get("/api/Dashboard/getModules");
    assertEquals("GET", get.method());
    assertEquals(Map.of("request", "UserId=7&AccountID=0", "value", "key-1"), get.query());
    assertEquals("Bearer tok-1", get.headers().get("authtoken"));
    assertEquals("key-1", get.headers().get("authkey"));
    assertEquals(CellGateClient.IS_E, get.headers().get("is_e"));
    assertEquals("blob", get.headers().get("responsetype"));

    assertEquals(1, units.size());
    assertEquals("Front gate", units.get(0).location());
    assertTrue(units.get(0).gates().size() == 1);
  }

  @Test
  void triggeringAGateSendsTheActuationEncryptedAndAcceptsAnEmptyReply() {
    final Watchman unit = client.devices().get(0);
    client.triggerGate(unit, unit.gates().get(0));

    final JsonNode data = decryptedData("/api/EVOhttp/EVOWTMMomentaryActuationRequest");
    assertEquals(15841, data.path("ModuleModemId").asLong());
    assertEquals(78847, data.path("ModulePortId").asLong());
    assertEquals("Gate", data.path("ModuleType").asString());
    assertEquals("01", data.path("PortNumber").asString());
    assertTrue(data.path("IsFromAppTrigger").asBoolean());
    assertEquals("key-1",
        decrypt(received.get("/api/EVOhttp/EVOWTMMomentaryActuationRequest").body().path("Value").asString()));
  }

  @Test
  void activitySendsTheLocationsAndAFilterOfExplicitNulls() {
    replies.put("/api/useraccesscode/GetPropertyLocationIDs", new Object[] {200, "[{\"ID\":18866}]"});
    replies.put("/api/Log/GetLogs", new Object[] {200, """
        {"data":[{"ID":179531924,"Description":"Trigger Gate","CreatedOn":"2026-11-27T19:14:45.757",
          "ModulePortId":78847,"IsSuccess":true,"User":{"Name":"Guest"}}],"inlineCount":1}"""});

    final var entries = client.activity(20);

    final Received locations = received.get("/api/useraccesscode/GetPropertyLocationIDs");
    assertEquals("POST", locations.method());
    assertEquals(Map.of("request", "AccountID=0&UserID=7", "value", "key-1"), locations.query());
    assertEquals("{}", locations.rawBody(), "this POST's body goes unencrypted");

    final JsonNode logs = decryptedData("/api/Log/GetLogs");
    assertEquals(List.of(18866L), MAPPER.convertValue(logs.path("propertyLocationIDList"),
        MAPPER.getTypeFactory().constructCollectionType(List.class, Long.class)));
    assertTrue(logs.path("filter").isObject());
    assertTrue(logs.path("filter").has("propertyId"), "filter fields go as explicit nulls");
    assertTrue(logs.path("filter").path("propertyId").isNull());
    assertTrue(logs.has("isGlobal") && logs.path("isGlobal").isNull());
    assertEquals(12147, logs.path("accountId").asLong());
    assertEquals("CreatedOn DESC", logs.path("sort").get(0).asString());

    assertEquals(1, entries.size());
    assertEquals("Trigger Gate", entries.get(0).description());
    assertEquals(78847L, entries.get(0).modulePortId());
  }

  /** With nothing to report the server switches to {@code Data} / {@code Count}, capitalised. */
  @Test
  void noActivityIsAnEmptyListNotNull() {
    replies.put("/api/useraccesscode/GetPropertyLocationIDs", new Object[] {200, "[]"});
    replies.put("/api/Log/GetLogs", new Object[] {200, "{\"Data\":null,\"Count\":0}"});
    assertEquals(List.of(), client.activity(20));
  }

  @Test
  void anImageComesBackAsBytesAndAMissingOneAsEmpty() {
    final byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
    replies.put("/api/Portal/LogImageUpload", new Object[] {200, jpeg});
    assertArrayEquals(jpeg, client.image("pic-1.jpg").orElseThrow());
    assertEquals("filename=pic-1.jpg", received.get("/api/Portal/LogImageUpload").query().get("request"));

    replies.put("/api/Portal/LogImageUpload", new Object[] {404, ""});
    assertTrue(client.image("gone.jpg").isEmpty());
  }

  @Test
  void a401EndsTheSessionAndTheNextCallSignsInAgain() {
    client.devices();
    assertEquals(1, logins.get());

    replies.put("/api/Dashboard/getModules", new Object[] {401, ""});
    final var e = assertThrows(CellGateException.class, client::devices);
    assertTrue(e.getMessage().contains("401"));

    replies.put("/api/Dashboard/getModules", new Object[] {200, MODULES_REPLY});
    client.devices();
    assertEquals(2, logins.get());
  }

  @Test
  void anotherErrorNamesTheStatusAndTheOperation() {
    replies.put("/api/Dashboard/getModules", new Object[] {500, ""});
    final var e = assertThrows(CellGateException.class, client::devices);
    assertEquals("CellGate answered HTTP 500 for getModules", e.getMessage());
  }

  @Test
  void aSignInWithoutATokenIsRefused() {
    replies.put("/api/AccountKit/userlogin", new Object[] {200, "{\"Message\":\"Invalid\"}"});
    final var e = assertThrows(CellGateException.class, client::signIn);
    assertTrue(e.getMessage().contains("refused"));
  }
}

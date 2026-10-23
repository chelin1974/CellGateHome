package io.github.chelin1974.cellgatehome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.cellgatehome.cellgate.CellGateCrypto;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CellGateCryptoTest {

  /** Decrypts what {@link CellGateCrypto} produced, to prove the chunk-and-"check" scheme. */
  private static String decrypt(String blob, PrivateKey key) throws Exception {
    StringBuilder out = new StringBuilder();
    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    for (String piece : blob.split("check")) {
      if (piece.isEmpty()) {
        continue;
      }
      cipher.init(Cipher.DECRYPT_MODE, key);
      out.append(new String(cipher.doFinal(Base64.getDecoder().decode(piece))));
    }
    return out.toString();
  }

  @Test
  @DisplayName("text is encrypted in 86-char chunks, each followed by 'check', and round-trips")
  void roundTrip() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(1024);
    KeyPair pair = gen.generateKeyPair();
    CellGateCrypto crypto = new CellGateCrypto(pair.getPublic());

    String text = "x".repeat(200); // spans three chunks
    String blob = crypto.encrypt(text);
    List<String> pieces = new ArrayList<>(List.of(blob.split("check")));
    assertEquals(3, pieces.size(), "200 chars at 86 per chunk is three pieces");
    assertEquals(text, decrypt(blob, pair.getPrivate()));
    assertEquals("", crypto.encrypt(""));
  }

  @Test
  @DisplayName("the body wraps Data and Value; the login body has an empty Value")
  void body() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(1024);
    KeyPair pair = gen.generateKeyPair();
    CellGateCrypto crypto = new CellGateCrypto(pair.getPublic());
    com.fasterxml.jackson.databind.JsonNode login =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(crypto.body("{\"a\":1}", null));
    assertEquals("", login.path("Value").asText());
    assertEquals("{\"a\":1}", decrypt(login.path("Data").asText(), pair.getPrivate()));

    com.fasterxml.jackson.databind.JsonNode withKey =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(crypto.body("{}", "sessionkey"));
    assertEquals("sessionkey", decrypt(withKey.path("Value").asText(), pair.getPrivate()));
  }

  @Test
  @DisplayName("the portal's real public key loads")
  void portalKey() {
    assertTrue(CellGateCrypto.portal().encrypt("hello").endsWith("check"));
  }
}

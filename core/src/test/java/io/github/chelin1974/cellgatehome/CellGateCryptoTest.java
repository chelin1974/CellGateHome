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
    final var out = new StringBuilder();
    final var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    for (final var piece : blob.split("check")) {
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
    final var gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(1024);
    final KeyPair pair = gen.generateKeyPair();
    final var crypto = new CellGateCrypto(pair.getPublic());

    final var text = "x".repeat(200); // spans three chunks
    final String blob = crypto.encrypt(text);
    final var pieces = new ArrayList<String>(List.of(blob.split("check")));
    assertEquals(3, pieces.size(), "200 chars at 86 per chunk is three pieces");
    assertEquals(text, decrypt(blob, pair.getPrivate()));
    assertEquals("", crypto.encrypt(""));
  }

  @Test
  @DisplayName("the portal's real public key loads")
  void portalKey() {
    assertTrue(CellGateCrypto.portal().encrypt("hello").endsWith("check"));
  }
}

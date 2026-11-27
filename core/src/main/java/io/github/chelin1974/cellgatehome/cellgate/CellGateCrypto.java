package io.github.chelin1974.cellgatehome.cellgate;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;

/**
 * The request encryption CellGate's portal uses.
 *
 * <p>Every request body, query string and session key the portal sends is encrypted with an RSA
 * public key that ships in the portal's own JavaScript: the text is cut into 86-character
 * pieces, each piece is RSA-encrypted (PKCS#1 v1.5) and base64-encoded, and the pieces are
 * joined, each followed by the literal {@code check}. Replies are plain JSON.
 *
 * <p>Only the encryption lives here. Where the encrypted text goes - the {@code Data} /
 * {@code Value} body, or the {@code request} / {@code value} query parameters - is in
 * {@code openapi.yaml}, and {@link CellGateClient} hands it to the generated client.
 *
 * <p>This is how the portal works, not a published protocol; see the README.
 */
public final class CellGateCrypto {

  /** The public key embedded in the portal at user.zapopen.com. */
  public static final String PORTAL_PUBLIC_KEY =
      "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDObZtjxfplZYRgo6TKZM9E6b3RVQpXTpKTOiqefTKEpT9//ru1x0rH"
          + "gqpsjcw1BoXFX3SuYRPn3ijCM/C9WHnc2PDjEgGu0KezIxvqE7nCjbHed7pff6fov6ZajFsiwcf2r3oOwCjWMW1C"
          + "hHP0ZYF2Ai1HmInarJutHTwE+Elb3QIDAQAB";

  static final int CHUNK = 86;

  private final PublicKey key;

  public CellGateCrypto(PublicKey key) {
    this.key = key;
  }

  public static CellGateCrypto portal() {
    return new CellGateCrypto(publicKey(PORTAL_PUBLIC_KEY));
  }

  public static PublicKey publicKey(String base64Der) {
    try {
      return KeyFactory.getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Der)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("bad RSA public key", e);
    }
  }

  /** Encrypts text the way the portal does. Empty text encrypts to an empty string. */
  public String encrypt(String text) {
    StringBuilder out = new StringBuilder();
    try {
      Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      for (int i = 0; i < text.length(); i += CHUNK) {
        String piece = text.substring(i, Math.min(text.length(), i + CHUNK));
        cipher.init(Cipher.ENCRYPT_MODE, key);
        out.append(Base64.getEncoder().encodeToString(cipher.doFinal(piece.getBytes(StandardCharsets.UTF_8))));
        out.append("check");
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not encrypt a CellGate request", e);
    }
    return out.toString();
  }
}

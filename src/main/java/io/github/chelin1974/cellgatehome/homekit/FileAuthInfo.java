package io.github.chelin1974.cellgatehome.homekit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.github.hapjava.server.HomekitAuthInfo;
import io.github.hapjava.server.impl.HomekitServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidAlgorithmParameterException;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HomeKit pairing state, persisted to a JSON file.
 *
 * <p>HomeKit pairs against the bridge's identity — its PIN, MAC, salt and private key —
 * plus the public key of every paired controller. All of it must survive a restart, or
 * iOS finds a stranger where its bridge used to be and the accessories go
 * "No Response" until you remove and re-add them.
 *
 * <p>The file is created on first run with freshly generated values, and holds the setup
 * PIN in plain text. It is written with owner-only permissions where the filesystem
 * supports it; treat it like a password file.
 */
public final class FileAuthInfo implements HomekitAuthInfo {

  private static final Logger log = LoggerFactory.getLogger(FileAuthInfo.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path file;
  private final State state;

  /** On-disk shape. Byte arrays are base64; the salt is a decimal string. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class State {
    public String pin;
    public String mac;
    public String salt;
    public String privateKey;
    public Map<String, User> users = new LinkedHashMap<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class User {
    public String publicKey;
    public boolean admin;
  }

  private FileAuthInfo(Path file, State state) {
    this.file = file;
    this.state = state;
  }

  /** Loads the pairing state, generating and saving a fresh identity on first run. */
  public static FileAuthInfo loadOrCreate(Path file) {
    try {
      if (Files.isReadable(file) && !isEmpty(file)) {
        State existing = MAPPER.readValue(Files.readString(file), State.class);
        if (existing.users == null) {
          existing.users = new LinkedHashMap<>();
        }
        FileAuthInfo info = new FileAuthInfo(file, existing);
        log.info(
            "loaded HomeKit pairing state from {} ({} paired controller(s))",
            file,
            existing.users.size());
        return info;
      }

      if (Files.exists(file)) {
        // An empty file is what a write that failed part-way leaves behind - most often a
        // full disk, since the old save() truncated before writing. It holds no pairings, so
        // there is nothing to lose by starting over, and refusing to start instead left the
        // bridge stuck in a retry loop over a file it had created itself.
        //
        // Deliberately only for an EMPTY file. Content that is present but unparseable may
        // be a recoverable file with real pairings in it, and regenerating the identity over
        // the top of that would silently un-pair every controller.
        log.warn(
            "{} is empty - generating a new HomeKit identity. If controllers were paired "
                + "before, they will need to be re-added in the Home app.",
            file);
      }

      State fresh = new State();
      fresh.pin = HomekitServer.generatePin();
      fresh.mac = HomekitServer.generateMac();
      fresh.salt = HomekitServer.generateSalt().toString();
      fresh.privateKey = Base64.getEncoder().encodeToString(HomekitServer.generateKey());

      FileAuthInfo info = new FileAuthInfo(file, fresh);
      info.save();
      log.info("created new HomeKit pairing state at {}", file);
      return info;
    } catch (IOException e) {
      throw new UncheckedIOException("could not read or create " + file, e);
    } catch (JacksonException e) {
      throw new UncheckedIOException("could not read or create " + file, new IOException(e.getMessage(), e));
    } catch (InvalidAlgorithmParameterException e) {
      throw new IllegalStateException("could not generate a HomeKit private key", e);
    }
  }

  @Override
  public String getPin() {
    return state.pin;
  }

  @Override
  public String getMac() {
    return state.mac;
  }

  @Override
  public BigInteger getSalt() {
    return new BigInteger(state.salt);
  }

  @Override
  public byte[] getPrivateKey() {
    return Base64.getDecoder().decode(state.privateKey);
  }

  @Override
  public void createUser(String username, byte[] publicKey) {
    createUser(username, publicKey, state.users.isEmpty());
  }

  @Override
  public void createUser(String username, byte[] publicKey, boolean isAdmin) {
    User user = new User();
    user.publicKey = Base64.getEncoder().encodeToString(publicKey);
    user.admin = isAdmin;
    state.users.put(username, user);
    save();
    log.info("paired with controller {}{}", username, isAdmin ? " (admin)" : "");
  }

  @Override
  public void removeUser(String username) {
    if (state.users.remove(username) != null) {
      save();
      log.info("unpaired controller {}", username);
    }
  }

  @Override
  public byte[] getUserPublicKey(String username) {
    User user = state.users.get(username);
    return user == null ? null : Base64.getDecoder().decode(user.publicKey);
  }

  @Override
  public Collection<String> listUsers() {
    return state.users.keySet();
  }

  @Override
  public boolean userIsAdmin(String username) {
    User user = state.users.get(username);
    return user != null && user.admin;
  }

  @Override
  public boolean hasUser() {
    return !state.users.isEmpty();
  }

  /** True for a file that exists but holds nothing, or only whitespace. */
  private static boolean isEmpty(Path file) throws IOException {
    return Files.size(file) == 0L || Files.readString(file).isBlank();
  }

  /**
   * Writes the state to a temporary file beside the real one and moves it into place.
   *
   * <p>Writing the file directly truncated it first, so a write that failed half way - a
   * full disk is the usual way - replaced a good file with an empty or partial one and took
   * every pairing with it. That is the one failure this class exists to prevent: losing this
   * file means every controller sees a stranger where its bridge used to be.
   *
   * <p>A move within a directory is atomic, so the real file only ever holds a complete
   * document: either the previous one or the new one, never a half of either. The temporary
   * file is given owner-only permissions before the move rather than after, so the state is
   * never briefly world-readable, and is cleaned up if anything fails.
   *
   * <p>AccessoryIds in this package already wrote its file this way; this brings the state
   * that actually matters into line with it.
   */
  private void save() {
    Path parent = file.toAbsolutePath().getParent();
    Path temporary = null;
    try {
      if (parent != null) {
        Files.createDirectories(parent);
      }
      String document = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(state);

      // In the same directory, so the move cannot cross a filesystem boundary and fall back
      // to a copy - which would reintroduce exactly the partial write this avoids.
      temporary = Files.createTempFile(parent, ".cellgate-home.state", ".tmp");
      Files.writeString(temporary, document);
      restrictPermissions(temporary);
      Files.move(
          temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      temporary = null;
    } catch (IOException e) {
      throw new UncheckedIOException("could not save HomeKit pairing state to " + file, e);
    } catch (JacksonException e) {
      throw new UncheckedIOException(
          "could not save HomeKit pairing state to " + file, new IOException(e.getMessage(), e));
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException suppressed) {
          log.debug("could not remove the temporary state file {}", temporary, suppressed);
        }
      }
    }
  }

  /** Best effort: POSIX only, and silently skipped elsewhere (e.g. Windows). */
  private static void restrictPermissions(Path target) {
    try {
      Files.setPosixFilePermissions(
          target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException | IOException ignored) {
      // Not a POSIX filesystem, or the file was removed underneath us.
    }
  }
}

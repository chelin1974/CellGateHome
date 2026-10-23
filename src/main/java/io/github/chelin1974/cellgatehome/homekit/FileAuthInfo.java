package io.github.chelin1974.cellgatehome.homekit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hapjava.server.HomekitAuthInfo;
import io.github.hapjava.server.impl.HomekitServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
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
      if (Files.isReadable(file)) {
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

  private void save() {
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(state));
      restrictPermissions();
    } catch (IOException e) {
      throw new UncheckedIOException("could not save HomeKit pairing state to " + file, e);
    }
  }

  /** Best effort: POSIX only, and silently skipped elsewhere (e.g. Windows). */
  private void restrictPermissions() {
    try {
      Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException | IOException ignored) {
      // Not a POSIX filesystem, or the file was removed underneath us.
    }
  }
}

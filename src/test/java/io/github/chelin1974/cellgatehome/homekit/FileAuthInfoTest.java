package io.github.chelin1974.cellgatehome.homekit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HomeKit pairing state on disk.
 *
 * <p>Losing this file un-pairs every controller, so the cases that matter here are the ones
 * where a write or a read goes wrong rather than the happy path.
 */
class FileAuthInfoTest {

  @TempDir Path directory;

  private Path state() {
    return directory.resolve("cellgate-home.state.json");
  }

  @Test
  void createsAFreshIdentityWhenThereIsNoFile() {
    FileAuthInfo info = FileAuthInfo.loadOrCreate(state());

    assertNotNull(info.getPin());
    assertNotNull(info.getMac());
    assertTrue(Files.exists(state()));
    assertFalse(info.hasUser());
  }

  @Test
  void keepsTheIdentityAcrossReloads() {
    FileAuthInfo first = FileAuthInfo.loadOrCreate(state());
    String pin = first.getPin();
    String mac = first.getMac();

    FileAuthInfo second = FileAuthInfo.loadOrCreate(state());

    assertEquals(pin, second.getPin());
    assertEquals(mac, second.getMac());
  }

  @Test
  void keepsPairedControllersAcrossReloads() {
    FileAuthInfo first = FileAuthInfo.loadOrCreate(state());
    first.createUser("controller-1", new byte[] {1, 2, 3});

    FileAuthInfo second = FileAuthInfo.loadOrCreate(state());

    assertTrue(second.hasUser());
    assertEquals(List.of("controller-1"), List.copyOf(second.listUsers()));
    assertArrayEqualsBase64(new byte[] {1, 2, 3}, second.getUserPublicKey("controller-1"));
  }

  private static void assertArrayEqualsBase64(byte[] expected, byte[] actual) {
    assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
  }

  /**
   * The failure this was reported for. A write that died part-way - a full disk is the usual
   * way - left a zero-byte file, and the bridge then refused to start with "No content to
   * map due to end-of-input", stuck in a retry loop over a file it had written itself.
   */
  @Test
  void treatsAnEmptyFileAsNoStateYet() throws IOException {
    Files.writeString(state(), "");

    FileAuthInfo info = FileAuthInfo.loadOrCreate(state());

    assertNotNull(info.getPin());
    assertFalse(info.hasUser());
    assertTrue(Files.size(state()) > 0, "the empty file should have been replaced");
  }

  @Test
  void treatsAWhitespaceOnlyFileAsNoStateYet() throws IOException {
    Files.writeString(state(), "   \n\t\n");

    FileAuthInfo info = FileAuthInfo.loadOrCreate(state());

    assertNotNull(info.getPin());
    assertFalse(info.hasUser());
  }

  /**
   * Deliberately NOT salvaged. Content that is present but unparseable may be a recoverable
   * file with real pairings in it, and generating a new identity over the top of it would
   * silently un-pair every controller - the exact outcome this class exists to avoid. It has
   * to fail loudly so the file can be looked at.
   */
  @Test
  void refusesToOverwriteAFileItCannotParse() throws IOException {
    Files.writeString(state(), "{\"pin\": \"031-45-154\", \"users\": {\"a\":");

    UncheckedIOException thrown =
        assertThrows(UncheckedIOException.class, () -> FileAuthInfo.loadOrCreate(state()));

    assertTrue(thrown.getMessage().contains("could not read or create"));
    assertEquals(
        "{\"pin\": \"031-45-154\", \"users\": {\"a\":",
        Files.readString(state()),
        "the unparseable file must be left exactly as it was");
  }

  /** A move into place, so the real file is never a partial document. */
  @Test
  void leavesNoTemporaryFilesBehind() throws IOException {
    FileAuthInfo info = FileAuthInfo.loadOrCreate(state());
    info.createUser("controller-1", new byte[] {4, 5, 6});
    info.createUser("controller-2", new byte[] {7, 8, 9});

    try (Stream<Path> entries = Files.list(directory)) {
      assertEquals(
          List.of("cellgate-home.state.json"),
          entries.map(path -> path.getFileName().toString()).sorted().toList());
    }
  }

  /**
   * The file holds the setup PIN in plain text, and the move must not widen it: permissions
   * are set on the temporary file before it goes into place, not after.
   */
  @Test
  void keepsTheFileOwnerOnly() throws IOException {
    FileAuthInfo info = FileAuthInfo.loadOrCreate(state());
    info.createUser("controller-1", new byte[] {1});

    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(state());

    assertEquals(PosixFilePermissions.fromString("rw-------"), permissions);
  }

  /**
   * A save that cannot complete must leave nothing behind, so the next start does not find a
   * stray temporary file where the state should be.
   *
   * <p>Failure is induced by making the destination a non-empty directory, which the move
   * cannot replace. The case this is really standing in for is a full disk, which cannot be
   * provoked portably from a test - that was verified separately against a 256 KB tmpfs:
   * the previous state survived intact, the controller paired before the failure was still
   * there on reload, and the error read "could not save HomeKit pairing state to
   * ...state.json: No space left on device".
   */
  @Test
  void leavesNothingBehindWhenASaveCannotComplete() throws IOException {
    Path blocked = directory.resolve("blocked.state.json");
    Files.createDirectory(blocked);
    Files.writeString(blocked.resolve("occupant"), "in the way");

    FileAuthInfo info = FileAuthInfo.loadOrCreate(directory.resolve("real.state.json"));

    // Point a second instance at the blocked path; its first save cannot land.
    assertThrows(UncheckedIOException.class, () -> FileAuthInfo.loadOrCreate(blocked));

    try (Stream<Path> entries = Files.list(directory)) {
      List<String> names = entries.map(p -> p.getFileName().toString()).sorted().toList();
      assertEquals(List.of("blocked.state.json", "real.state.json"), names);
    }
    assertNotNull(info.getPin());
  }

  @Test
  void createsMissingParentDirectories() {
    Path nested = directory.resolve("a/b/c/cellgate-home.state.json");

    FileAuthInfo info = FileAuthInfo.loadOrCreate(nested);

    assertNotNull(info.getPin());
    assertTrue(Files.exists(nested));
  }

  @Test
  void removesAnUnpairedController() {
    FileAuthInfo info = FileAuthInfo.loadOrCreate(state());
    info.createUser("controller-1", new byte[] {1});
    info.createUser("controller-2", new byte[] {2});

    info.removeUser("controller-1");

    assertEquals(List.of("controller-2"), List.copyOf(FileAuthInfo.loadOrCreate(state()).listUsers()));
  }
}

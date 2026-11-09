package io.github.chelin1974.cellgatehome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the classes this bridge keeps byte-identical with its sibling bridges.
 *
 * <p>DaikinHome, SpanHome and CellGateHome are separate repositories that deploy on their own,
 * but the HomeKit plumbing underneath them is the same code: the pairing-state file, the mDNS
 * watchdog, the accessory-ID and layout bookkeeping, the LAN address picking. Keeping them as
 * deliberate mirrors rather than a published library avoids a cross-repository release for
 * every change, at the cost that a fix applied to one copy can silently miss the others. That
 * has already happened: an atomic-write fix for the pairing state landed in one repository and
 * sat there while the other two kept the bug.
 *
 * <p>So each repository records the same digest for each mirrored file, over a normalised form
 * that ignores the things that legitimately differ - the package name, the bridge's own name
 * and the file-name slug. Edit one copy and its digest changes and this test fails, naming the
 * file.
 *
 * <h2>When this test fails</h2>
 *
 * <p>If the change was deliberate, it belongs in all three. Port it, then update
 * {@code mirrored-sources.sha256} in every repository with the new digest - the message below
 * prints it. If it was not deliberate, the copies have drifted and the fix is to make them
 * agree again.
 *
 * <p>Repositories: https://github.com/chelin1974/DaikinHome,
 * https://github.com/chelin1974/SpanHome, https://github.com/chelin1974/CellGateHome
 */
class MirroredSourcesTest {

  /** Where this repository keeps each mirrored file, relative to the module root. */
  private static final Map<String, String> MIRRORED = mirrored();

  private static Map<String, String> mirrored() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("AccessoryIds", "src/main/java/io/github/chelin1974/cellgatehome/homekit/AccessoryIds.java");
    files.put("FileAuthInfo", "src/main/java/io/github/chelin1974/cellgatehome/homekit/FileAuthInfo.java");
    files.put("LayoutVersion", "src/main/java/io/github/chelin1974/cellgatehome/homekit/LayoutVersion.java");
    files.put("MdnsWatchdog", "src/main/java/io/github/chelin1974/cellgatehome/homekit/MdnsWatchdog.java");
    files.put("NetworkAddresses", "src/main/java/io/github/chelin1974/cellgatehome/homekit/NetworkAddresses.java");
    return files;
  }

  @Test
  void mirroredSourcesMatchTheRecordedDigests() throws IOException {
    Map<String, String> expected = readDigests();
    Map<String, String> actual = new LinkedHashMap<>();

    for (Map.Entry<String, String> entry : MIRRORED.entrySet()) {
      Path file = Path.of(entry.getValue());
      assertTrue(
          Files.isReadable(file),
          "mirrored source is missing: " + file + " (did it move? update MIRRORED)");
      actual.put(entry.getKey(), digest(Files.readString(file)));
    }

    // One assertion over the whole map, so a failure lists every file that moved rather than
    // stopping at the first.
    assertEquals(
        render(expected),
        render(actual),
        "A mirrored source no longer matches the digest recorded in "
            + "src/test/resources/mirrored-sources.sha256.\n"
            + "If the change was deliberate, port it to the sibling bridges "
            + "(DaikinHome / SpanHome / CellGateHome) and update that file in all of them with "
            + "the digests shown as 'actual' here.\n"
            + "If not, the copies have drifted and should be made to agree again.");
  }

  /**
   * The digest covers a normalised form: the {@code package} line is dropped, and the three
   * things that legitimately differ between repositories are replaced with placeholders - the
   * bridge name ({@code DaikinHome}, {@code spanhome}, ...), the file-name slug
   * ({@code daikin-home}, {@code span-home}, ...) and slug-prefixed temporary file names.
   *
   * <p>Line endings are normalised too, so a checkout with CRLF does not fail this.
   */
  static String digest(String source) {
    String normalised =
        source
            .replace("\r\n", "\n")
            .lines()
            .filter(line -> !line.startsWith("package "))
            .collect(Collectors.joining("\n"))
            .replaceAll("(daikin|span|cellgate)-home", "SLUG")
            .replaceAll("(?i)(daikin|span|cellgate)home", "BRIDGE")
            .replaceAll("\\.(daikin|span|cellgate)-", ".SLUG-");

    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(normalised.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JVM", e);
    }
  }

  private static Map<String, String> readDigests() throws IOException {
    Path file = Path.of("src/test/resources/mirrored-sources.sha256");
    Map<String, String> digests = new LinkedHashMap<>();
    for (String line : Files.readAllLines(file)) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      List<String> parts = List.of(trimmed.split("\\s+"));
      digests.put(parts.get(0), parts.get(1));
    }
    return digests;
  }

  private static String render(Map<String, String> digests) {
    return digests.entrySet().stream()
        .map(e -> e.getKey() + " " + e.getValue())
        .sorted()
        .collect(Collectors.joining("\n"));
  }
}

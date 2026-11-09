package io.github.chelin1974.cellgatehome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every file the bridge writes lands in the data directory.
 *
 * <p>This is the test that makes that a guarantee rather than a convention. Three files are
 * written - pairing state, accessory IDs, the layout number - and they used to hang off
 * {@code storagePath}'s parent, so pointing the pairing state somewhere else quietly took the
 * other two with it, and a container could end up with some on its mounted volume and the rest
 * on the writable layer, where they vanish on the next recreate. Losing the pairing state
 * un-pairs every controller.
 */
class DataDirectoryTest {

  @TempDir Path home;

  private CellGateConfig configWith(String extra) throws IOException {
    Path directory = Files.createDirectories(home.resolve("checkout"));
    Path file = directory.resolve("cellgate.config.json");
    Files.writeString(
        file,
        "{\"email\":\"a@b.com\",\"password\":\"x\"" + (extra.isEmpty() ? "" : "," + extra) + "}");
    return CellGateConfig.load(file);
  }

  @Test
  @DisplayName("with no dataDirectory, everything sits beside the config file")
  void defaultsToTheConfigDirectory() throws IOException {
    CellGateConfig config = configWith("");

    assertEquals(home.resolve("checkout"), config.dataDirectory());
    assertEquals(home.resolve("checkout/cellgate-home.state.json"), config.storagePath());
  }

  /** The container case: one setting puts every written file on the mounted volume. */
  @Test
  @DisplayName("a relative dataDirectory is resolved against the config file")
  void relativeDataDirectory() throws IOException {
    CellGateConfig config = configWith("\"dataDirectory\":\"data\"");

    assertEquals(home.resolve("checkout/data"), config.dataDirectory());
    assertEquals(home.resolve("checkout/data/cellgate-home.state.json"), config.storagePath());
  }

  @Test
  @DisplayName("an absolute dataDirectory is taken as given")
  void absoluteDataDirectory() throws IOException {
    CellGateConfig config = configWith("\"dataDirectory\":\"/data\"");

    assertEquals(Path.of("/data"), config.dataDirectory());
    assertEquals(Path.of("/data/cellgate-home.state.json"), config.storagePath());
  }

  /**
   * storagePath still wins for the state file, since someone may deliberately keep it on a
   * backed-up volume - but it no longer drags the other two files along with it.
   */
  @Test
  @DisplayName("storagePath moves only the state file, not the rest")
  void storagePathNoLongerMovesEverything() throws IOException {
    CellGateConfig config =
        configWith("\"dataDirectory\":\"/data\",\"storagePath\":\"/elsewhere/state.json\"");

    assertEquals(Path.of("/elsewhere/state.json"), config.storagePath());
    assertEquals(Path.of("/data"), config.dataDirectory());
  }

  @Test
  @DisplayName("a relative storagePath stays inside the data directory")
  void relativeStoragePathStaysInside() throws IOException {
    CellGateConfig config =
        configWith("\"dataDirectory\":\"data\",\"storagePath\":\"state.json\"");

    assertEquals(home.resolve("checkout/data/state.json"), config.storagePath());
  }
}

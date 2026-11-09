package io.github.chelin1974.cellgatehome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import org.junit.jupiter.api.Test;

/** {@link Failures#describe(Throwable)}. */
class FailuresTest {

  /**
   * The case this exists for. Logging only the outermost message said the pairing state
   * could not be saved but not that the disk was full, and a full disk and an unwritable
   * volume need completely different things done about them.
   */
  @Test
  void includesTheCauseThatSaysWhatWentWrong() {
    FileSystemException cause =
        new FileSystemException("/data/cellgate-home.state.json", null, "No space left on device");
    UncheckedIOException thrown =
        new UncheckedIOException(
            "could not save HomeKit pairing state to /data/cellgate-home.state.json", cause);

    assertEquals(
        "could not save HomeKit pairing state to /data/cellgate-home.state.json: "
            + "/data/cellgate-home.state.json: No space left on device",
        Failures.describe(thrown));
  }

  @Test
  void joinsEveryDistinctMessageInTheChain() {
    Throwable thrown = new IllegalStateException("outer", new IOException("middle", new RuntimeException("inner")));

    assertEquals("outer: middle: inner", Failures.describe(thrown));
  }

  /** Wrappers routinely copy the cause's message upwards; saying it twice adds nothing. */
  @Test
  void doesNotRepeatAnIdenticalMessage() {
    Throwable thrown = new IllegalStateException("same", new IOException("same"));

    assertEquals("same", Failures.describe(thrown));
  }

  /** "null" in a log line tells nobody anything; the class name at least names the fault. */
  @Test
  void fallsBackToTheClassNameWhenAMessageIsMissing() {
    assertEquals("NullPointerException", Failures.describe(new NullPointerException()));
  }

  @Test
  void usesTheClassNameForABlankMessage() {
    assertEquals("IllegalStateException", Failures.describe(new IllegalStateException("   ")));
  }

  @Test
  void keepsAUsefulCauseBehindAMessagelessWrapper() {
    Throwable thrown = new RuntimeException(null, new IOException("No space left on device"));

    assertEquals("RuntimeException: No space left on device", Failures.describe(thrown));
  }

  /** A self-referencing chain must not hang the logger. */
  @Test
  void survivesACyclicChain() {
    IOException first = new IOException("first");
    IllegalStateException second = new IllegalStateException("second", first);
    first.initCause(second);

    assertEquals("second: first", Failures.describe(second));
  }

  @Test
  void handlesNull() {
    assertEquals("unknown failure", Failures.describe(null));
  }
}

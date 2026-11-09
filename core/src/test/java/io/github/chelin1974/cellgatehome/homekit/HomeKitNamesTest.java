package io.github.chelin1974.cellgatehome.homekit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HomeKitNames}, which this bridge shares verbatim with SpanHome.
 *
 * <p>It had no tests here at all, and its only specification was its javadoc - which the two
 * bridges wrote up with different examples. Both were correct, at different levels of
 * generality; these tests pin the behaviour so the shared copy cannot drift silently.
 */
class HomeKitNamesTest {

  @Test
  @DisplayName("names are cleaned to what recent iOS versions accept")
  void clean() {
    // Curly quotes are common in names typed on a phone; HomeKit only takes '.
    assertEquals("Maya's Gate", HomeKitNames.clean("Maya’s Gate", "x"));
    // Characters outside letters, numbers and a little punctuation become spaces.
    assertEquals("Side Gate", HomeKitNames.clean("Side / Gate", "x"));
    // A name has to start and end with a letter or number.
    assertEquals("Gate", HomeKitNames.clean("--Gate--", "x"));
    // Nothing usable left means the fallback.
    assertEquals("Gate", HomeKitNames.clean("///", "Gate"));
    assertEquals("Gate", HomeKitNames.clean(null, "Gate"));
    // At most 64 characters, not ending on punctuation.
    assertEquals(64, HomeKitNames.clean("G".repeat(80), "x").length());
  }

  @Test
  @DisplayName("a name already taken gets a number")
  void unique() {
    assertEquals("Gate 2", HomeKitNames.unique("Gate", List.of("gate")));
    assertEquals("Gate", HomeKitNames.unique("Gate", List.of()));
  }

  @Test
  @DisplayName("firmware strings become HomeKit x.y.z versions")
  void firmware() {
    // The first three runs of digits, each read as a number so leading zeros go.
    assertEquals("2.4.17", HomeKitNames.firmware("v2.4.17-b3"));
    assertEquals("3.202633.4", HomeKitNames.firmware("spanos3/r202633/04"));
    assertEquals("1.2.3", HomeKitNames.firmware("1.2.3"));

    // Fewer than three runs pads with zeros.
    assertEquals("2.4.0", HomeKitNames.firmware("2.4"));
    assertEquals("7.0.0", HomeKitNames.firmware("7"));

    // Nothing usable falls back.
    assertEquals("1.0.0", HomeKitNames.firmware(null));
    assertEquals("1.0.0", HomeKitNames.firmware(""));
    assertEquals("1.0.0", HomeKitNames.firmware("no digits here"));
  }
}

package io.github.chelin1974.cellgatehome;

import java.util.IdentityHashMap;
import java.util.Set;

/** Turning an exception into something a log reader can act on. */
public final class Failures {

  private Failures() {}

  /**
   * The whole cause chain as one line, innermost cause last.
   *
   * <p>Logging {@code e.getMessage()} alone loses exactly the part that says what to do.
   * "could not save HomeKit pairing state to /data/cellgate-home.state.json" has two very
   * different causes — the disk being full, or the volume not being writable by the
   * container's user — and the remedies have nothing in common. The wrapper's message names
   * the operation; only the cause names the fault. This returns both:
   *
   * <pre>
   *   could not save HomeKit pairing state to /data/cellgate-home.state.json: No space left on device
   *   could not save HomeKit pairing state to /data/cellgate-home.state.json: Permission denied
   * </pre>
   *
   * <p>Exceptions with a null message contribute their class name instead, so a chain of
   * them still reads as something rather than as "null". A message identical to the one
   * already shown is skipped, since JDK and library wrappers routinely copy the cause's
   * message upwards and repeating it adds nothing.
   */
  public static String describe(Throwable error) {
    if (error == null) {
      return "unknown failure";
    }

    StringBuilder description = new StringBuilder();
    String last = null;
    // Identity-based, because a cause chain can be cyclic and equals() on exceptions is
    // identity anyway; this just makes the guard explicit.
    Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    for (Throwable current = error; current != null && seen.add(current); ) {
      String message = current.getMessage();
      String part =
          message == null || message.isBlank()
              ? current.getClass().getSimpleName()
              : message.strip();

      if (!part.equals(last)) {
        if (!description.isEmpty()) {
          description.append(": ");
        }
        description.append(part);
        last = part;
      }
      current = current.getCause();
    }

    return description.toString();
  }
}

package io.github.chelin1974.cellgatehome.homekit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MdnsWatchdogTest {

  /** A probe whose outcome the test controls, so no network is involved. */
  private static final class StubProbe implements MdnsWatchdog.Probe {
    boolean fail;
    int sends;

    @Override
    public void send() throws IOException {
      sends++;
      if (fail) {
        throw new IOException("No route to host");
      }
    }
  }

  @Nested
  @DisplayName("the multicast probe")
  class Probing {

    @Test
    @DisplayName("tolerates a blip rather than restarting over a DHCP renew")
    void oneFailureIsNotFatal() {
      StubProbe probe = new StubProbe();
      List<String> deaths = new ArrayList<>();
      MdnsWatchdog watchdog = new MdnsWatchdog(probe, deaths::add, 3);

      probe.fail = true;
      watchdog.runProbe();

      assertTrue(watchdog.isHealthy());
      assertEquals(0, deaths.size());
    }

    @Test
    @DisplayName("declares the advertisement dead after enough consecutive failures")
    void repeatedFailuresAreFatal() {
      StubProbe probe = new StubProbe();
      List<String> deaths = new ArrayList<>();
      MdnsWatchdog watchdog = new MdnsWatchdog(probe, deaths::add, 3);

      probe.fail = true;
      watchdog.runProbe();
      watchdog.runProbe();
      watchdog.runProbe();

      assertFalse(watchdog.isHealthy());
      assertTrue(watchdog.deadReason().isPresent());
      String reason = watchdog.deadReason().orElseThrow();
      assertTrue(reason.contains("224.0.0.251"), reason);
      assertTrue(reason.contains("No route to host"), reason);
      assertEquals(1, deaths.size());
    }

    @Test
    @DisplayName("a success resets the run, so intermittent failures never add up")
    void successResetsTheCount() {
      StubProbe probe = new StubProbe();
      MdnsWatchdog watchdog = new MdnsWatchdog(probe, reason -> {}, 3);

      probe.fail = true;
      watchdog.runProbe();
      watchdog.runProbe();
      probe.fail = false;
      watchdog.runProbe();
      probe.fail = true;
      watchdog.runProbe();
      watchdog.runProbe();

      assertTrue(
          watchdog.isHealthy(),
          "two failures either side of a success must not count as four");
    }

    @Test
    @DisplayName("stops probing once dead, since JmDNS does not come back")
    void stopsProbingOnceDead() {
      StubProbe probe = new StubProbe();
      MdnsWatchdog watchdog = new MdnsWatchdog(probe, reason -> {}, 1);

      probe.fail = true;
      watchdog.runProbe();
      int sendsWhenDeclaredDead = probe.sends;
      watchdog.runProbe();
      watchdog.runProbe();

      assertEquals(sendsWhenDeclaredDead, probe.sends);
    }

    @Test
    @DisplayName("a probe that throws unchecked is still a probe failure")
    void runtimeExceptionsCount() {
      MdnsWatchdog watchdog =
          new MdnsWatchdog(
              () -> {
                throw new IllegalStateException("socket closed");
              },
              reason -> {},
              1);

      watchdog.runProbe();

      assertFalse(watchdog.isHealthy());
      assertTrue(watchdog.deadReason().orElseThrow().contains("socket closed"));
    }
  }

  @Nested
  @DisplayName("notifying the listener")
  class Notifying {

    @Test
    @DisplayName("fires once, however many times the failure is reported")
    void firesOnce() {
      StubProbe probe = new StubProbe();
      List<String> deaths = new ArrayList<>();
      MdnsWatchdog watchdog = new MdnsWatchdog(probe, deaths::add, 1);

      probe.fail = true;
      watchdog.runProbe();
      watchdog.runProbe();
      watchdog.runProbe();

      assertEquals(1, deaths.size());
    }

    @Test
    @DisplayName("a listener that throws does not stop the failure being recorded")
    void listenerFailureIsContained() {
      StubProbe probe = new StubProbe();
      AtomicBoolean called = new AtomicBoolean();
      MdnsWatchdog watchdog =
          new MdnsWatchdog(
              probe,
              reason -> {
                called.set(true);
                throw new IllegalStateException("listener blew up");
              },
              1);

      probe.fail = true;
      watchdog.runProbe();

      assertTrue(called.get());
      assertFalse(watchdog.isHealthy());
    }
  }

  @Nested
  @DisplayName("the query packet")
  class QueryPacket {

    @Test
    @DisplayName("is a well-formed single-question PTR query")
    void isWellFormed() {
      byte[] query = MdnsWatchdog.ptrQuery("_hap._tcp.local.");

      // Header: id 0, flags 0, exactly one question, no records.
      for (int i = 0; i <= 4; i++) {
        assertEquals(0, query[i], "header byte " + i + " should be zero");
      }
      assertEquals(1, query[5], "QDCOUNT should be 1");
      for (int i = 6; i < 12; i++) {
        assertEquals(0, query[i], "record counts must be zero at byte " + i);
      }

      // Name: length-prefixed labels, terminated by a zero byte.
      String name = new String(query, 12, query.length - 12, StandardCharsets.US_ASCII);
      assertTrue(name.contains("_hap"), name);
      assertTrue(name.contains("_tcp"), name);
      assertTrue(name.contains("local"), name);
      assertEquals(4, query[12], "first label \"_hap\" is four bytes");

      // Trailer: QTYPE = PTR (12), QCLASS = IN with the unicast-response bit set.
      assertEquals(0, query[query.length - 4]);
      assertEquals(12, query[query.length - 3], "QTYPE should be PTR");
      assertEquals((byte) 0x80, query[query.length - 2], "unicast-response bit");
      assertEquals((byte) 0x01, query[query.length - 1], "QCLASS should be IN");
    }

    @Test
    @DisplayName("ignores the trailing dot rather than emitting an empty label")
    void toleratesTheTrailingDot() {
      org.junit.jupiter.api.Assertions.assertArrayEquals(
          MdnsWatchdog.ptrQuery("_hap._tcp.local"),
          MdnsWatchdog.ptrQuery("_hap._tcp.local."));
    }
  }
}

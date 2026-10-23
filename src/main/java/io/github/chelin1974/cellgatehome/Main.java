package io.github.chelin1974.cellgatehome;

import io.github.chelin1974.cellgatehome.CellGateConfig.ConfigException;
import io.github.chelin1974.cellgatehome.cellgate.Watchman;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <pre>
 *   java -jar cellgate-home.jar --list        sign in, print the gates and cameras, and exit
 *   java -jar cellgate-home.jar               run the HomeKit bridge and dashboard
 * </pre>
 */
public final class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  private Main() {}

  public static void main(String[] args) {
    Map<String, String> options = parseArgs(args);
    if (options.containsKey("help") || options.containsKey("h")) {
      System.out.println("""
          usage: java -jar cellgate-home.jar [options]

            --config <path>   configuration file (default: cellgate.config.json)
            --list            sign in, print the gates and cameras, and exit
            --help            show this message
          """);
      return;
    }

    Path configPath = Path.of(options.getOrDefault("config", "cellgate.config.json"));
    try {
      CellGateConfig config = CellGateConfig.load(configPath);
      if (options.containsKey("list")) {
        System.exit(list(config));
      }
      run(config);
    } catch (ConfigException e) {
      log.error("{}", e.getMessage());
      System.exit(2);
    } catch (Exception e) {
      log.error("could not start: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  private static int list(CellGateConfig config) {
    CellGateHomeBridge bridge = new CellGateHomeBridge(config);
    List<Watchman> units = bridge.listDevices();
    if (units.isEmpty()) {
      System.out.println("No units on this CellGate account.");
      return 0;
    }
    for (Watchman unit : units) {
      System.out.printf("%s (%s) %s, serial %s%s%n",
          unit.location(), unit.modelName(), unit.hardwareModel(), unit.serial(),
          unit.online() ? "" : " [offline]");
      for (Watchman.Port port : unit.ports()) {
        String role = port.isGate() ? "GATE (openable)"
            : port.isCamera() ? "camera (take picture)"
            : port.isSensor() ? "status sensor" : port.entryType();
        String status = port.statusText() == null ? "" : " — " + port.statusText();
        System.out.printf("  port %-3s %-22s %s%s%n", port.number(), port.name(), role, status);
      }
    }
    return 0;
  }

  private static void run(CellGateConfig config) throws Exception {
    CellGateHomeBridge bridge = new CellGateHomeBridge(config);
    Runtime.getRuntime().addShutdownHook(new Thread(bridge::close, "cellgate-home-shutdown"));
    bridge.start();
    bridge.awaitTermination();
  }

  /** Parses {@code --key value} and bare {@code --flag} options. */
  static Map<String, String> parseArgs(String[] args) {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("--") || arg.startsWith("-")) {
        String key = arg.replaceFirst("^-+", "");
        if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
          options.put(key, args[++i]);
        } else {
          options.put(key, "");
        }
      }
    }
    return options;
  }
}

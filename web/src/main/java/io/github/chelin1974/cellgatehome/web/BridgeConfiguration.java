package io.github.chelin1974.cellgatehome.web;

import io.github.chelin1974.cellgatehome.CellGateConfig;
import io.github.chelin1974.cellgatehome.CellGateHomeBridge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the bridge from {@code cellgate.config.json} and hands it to Spring. */
@Configuration
public class BridgeConfiguration {

  private static final Logger log = LoggerFactory.getLogger(BridgeConfiguration.class);

  /** Looked at in order when {@code cellgate.config-file} is not set. */
  static final List<String> DEFAULT_LOCATIONS =
      List.of("data/cellgate.config.json", "cellgate.config.json");

  /**
   * The configuration file, located the same way for every route into this service.
   *
   * @throws IllegalStateException with the candidate paths named when there is no file, since a
   *     bridge with no configuration cannot start and guessing would be worse
   */
  static Path locate(CellGateProperties properties) {
    String configured = properties.getConfigFile();
    if (configured != null && !configured.isBlank()) {
      Path named = Path.of(configured.strip());
      if (!Files.isReadable(named)) {
        throw new IllegalStateException(
            "cannot read the configuration file "
                + named.toAbsolutePath()
                + ", named by cellgate.config-file (CELLGATE_CONFIG_FILE)");
      }
      return named;
    }

    for (String candidate : DEFAULT_LOCATIONS) {
      Path path = Path.of(candidate);
      if (Files.isReadable(path)) {
        return path;
      }
    }

    throw new IllegalStateException(
        "no configuration file: looked for "
            + String.join(" and ", DEFAULT_LOCATIONS)
            + " under "
            + Path.of("").toAbsolutePath()
            + ". Copy cellgate.config.example.json into data/ and fill it in, or set "
            + "cellgate.config-file (CELLGATE_CONFIG_FILE).");
  }

  @Bean
  CellGateConfig cellGateConfig(CellGateProperties properties) {
    Path file = locate(properties);
    CellGateConfig config = CellGateConfig.load(file);
    log.info("configuration read from {}", file.toAbsolutePath());
    return config;
  }

  @Bean
  CellGateHomeBridge cellGateHomeBridge(CellGateConfig config) {
    CellGateHomeBridge bridge = new CellGateHomeBridge(config);

    // Only one of the two may bind the dashboard port. Spring is already listening on it by the
    // time the bridge starts - application.yml takes server.port from the same setting - so the
    // bridge's own small HTTP server stands down. dashboardPort on this config is a final
    // field, so this is a flag on the bridge rather than a value overwritten underneath it.
    bridge.dashboardServedElsewhere();
    log.info("the gate dashboard is served by this service on port {}", config.dashboardPort());
    return bridge;
  }
}

package io.github.chelin1974.cellgatehome.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code span.*} block, which is only about where to find the configuration file.
 *
 * <p>Everything else the bridge needs stays in {@code cellgate-home.config.json}, read by the
 * same {@code CellGateConfig} the CLI uses. That is deliberate: this bridge's settings
 * include nested lists - {@code switchCircuits}, {@code readOnlyCircuits}, the panel list,
 * room names - which have no sensible spelling as environment variables, and splitting them
 * across two formats would give the two flavours different capabilities.
 */
@ConfigurationProperties(prefix = "cellgate")
public class CellGateProperties {

  /**
   * The configuration file. Left unset, {@code data/cellgate-home.config.json} is used when it
   * exists, and {@code cellgate-home.config.json} in the working directory otherwise.
   *
   * <p>The data folder is looked at first because that is where everything this bridge writes
   * lives - pairing state, the panel credentials earned at registration, accessory IDs, the
   * layout number - all resolved relative to this file. Keeping the file with them means one
   * directory to mount into a container and one directory to back up.
   */
  private String configFile;

  /**
   * Overrides {@code dataDirectory} in the configuration file.
   *
   * <p>For a deployment that must put the written files somewhere specific without editing the
   * user's config. The container needs this: its config is on a read-only mount, so defaulting
   * the data directory to the config's own directory would make every write fail.
   */
  private String dataDirectory;

  public String getDataDirectory() {
    return dataDirectory;
  }

  public void setDataDirectory(String dataDirectory) {
    this.dataDirectory = dataDirectory;
  }

  public String getConfigFile() {
    return configFile;
  }

  public void setConfigFile(String configFile) {
    this.configFile = configFile;
  }
}

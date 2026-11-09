package io.github.chelin1974.cellgatehome.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The bridge as a Spring Boot service.
 *
 * <pre>
 *   java -jar web/target/cellgate-home-web.jar
 *   CELLGATE_CONFIG_FILE=/data/cellgate-home.config.json java -jar web/target/cellgate-home-web.jar
 * </pre>
 *
 * <p>Same bridge as the CLI flavour, same {@code cellgate-home.config.json}. What this adds is
 * the gate dashboard served by Tomcat rather than by the bridge's own small HTTP server,
 * a REST API, and actuator health with liveness and readiness groups - so Kubernetes and
 * systemd can both ask the right questions.
 *
 * <p>The bridge's built-in dashboard is stood down here: {@code dashboardPort} is forced to 0
 * so that only one of the two binds a port. Spring then listens on whatever
 * {@code dashboardPort} said, so the dashboard stays where you had it.
 */
@SpringBootApplication
@EnableConfigurationProperties(CellGateProperties.class)
public class CellGateWebApplication {

  public static void main(String[] args) {
    SpringApplication.run(CellGateWebApplication.class, args);
  }
}

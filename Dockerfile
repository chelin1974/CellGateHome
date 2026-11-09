# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build stage
FROM maven:3.10-eclipse-temurin-27 AS build
WORKDIR /src

COPY pom.xml ./
COPY core core
COPY cli cli
COPY web web

# A BuildKit cache mount keeps the Maven repository between builds, which is both
# simpler and more reliable than a separate dependency-resolution layer.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

# -------------------------------------------------------------- runtime stage
FROM eclipse-temurin:27-jre

LABEL org.opencontainers.image.title="CellGate HomeKit bridge" \
      org.opencontainers.image.description="CellGate gate and doorbell in Apple Home, with a dashboard" \
      org.opencontainers.image.source="https://github.com/chelin1974/CellGateHome" \
      org.opencontainers.image.licenses="MIT"

RUN useradd --system --uid 10001 --create-home --home-dir /app --shell /usr/sbin/nologin cellgate \
 && mkdir -p /data /config \
 && chown cellgate:cellgate /data

WORKDIR /app
COPY --from=build --chown=cellgate:cellgate /src/web/target/cellgate-home-web.jar ./cellgate-home-web.jar

USER 10001

# HomeKit pairing state. It MUST outlive the container: lose it and every paired
# controller sees a stranger where its bridge used to be, and the accessories go
# "No Response" until the bridge is removed and re-added in the Home app.
VOLUME ["/data"]

# 8090 is the dashboard. 51829 is the HomeKit accessory port.
#
# Neither is the whole story: HomeKit discovery is mDNS multicast on
# 224.0.0.251:5353, which a container on a bridge/overlay network cannot send or
# receive. Run this with the host's network (`--network host`, or hostNetwork in
# Kubernetes) or the Home app will never find the bridge. See deploy/k8s/README.md.
EXPOSE 8090/tcp 51829/tcp

# The config file holds the CellGate password, so it is mounted in rather than baked
# into the image - from a Secret under Kubernetes, a bind mount under Docker.
#
# No shell, so the JVM is PID 1 and gets SIGTERM directly.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/cellgate-home-web.jar"]
# Where the config is in this image. The CLI took "--config <path>"; the Spring Boot flavour
# takes a property, and would have parsed that flag as an unknown option and ignored it -
# leaving the bridge looking for a config beside the jar. An ENV rather than a CMD so compose
# and Kubernetes inherit it without each having to pass it.
ENV CELLGATE_CONFIG_FILE=/config/cellgate.config.json

# syntax=docker/dockerfile:1
#
# wlwl-backend (Java port of the PHP backend) — container image.
#
# Two stages so the runtime image carries no Maven, no sources and no test
# classpath. The important property for this project is that the image changes
# nothing about *behaviour*: it only sets the environment variables that
# application.yml already reads (DB_*, PORT, WLWL_UPLOAD_DIR, TZ). No endpoint,
# header, port or path is added or altered, so the Vue client cutover is the
# same whether the jar is run bare (see README) or in this container.
#
# Build:
#   docker build -t wlwl-backend:0.1.0 .
#   docker build -t wlwl-backend:0.1.0 --build-arg MVN_ARGS="-B verify" .   # run the parity suite too
#
# Run (DB on the host / another container):
#   docker run -p 8080:8080 \
#     -e DB_HOST=db -e DB_NAME=wlwl -e DB_USER=wlwl -e DB_PASSWORD=*** \
#     -e WLWL_UPLOAD_DIR=/data/uploads -v wlwl-uploads:/data/uploads \
#     wlwl-backend:0.1.0

# ---------------------------------------------------------------- build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build

# pom.xml first, so the dependency layer is cached and only invalidated when the
# dependency set actually changes — source edits do not re-download the world.
COPY pom.xml ./
RUN mvn -B --no-transfer-progress -q dependency:go-offline

COPY src ./src

# Default skips tests: CI (.github/workflows/ci.yml) already runs `mvn verify`,
# i.e. the 254-test parity suite, on every push. Pass
# --build-arg MVN_ARGS="-B verify" when you want the image build to be the gate.
ARG MVN_ARGS="-B -DskipTests"
RUN mvn ${MVN_ARGS} --no-transfer-progress package \
 && cp target/wlwl-backend-*.jar /build/app.jar

# -------------------------------------------------------------- runtime stage
FROM eclipse-temurin:21-jre-jammy

# curl is only for the HEALTHCHECK below. Removed lists keep the layer small.
# Pinning curl's version (DL3008) would break whenever the base image's Ubuntu
# point release moves; the base image tag is the pinned unit here.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Unprivileged at runtime, like the jar should be. UID/GID fixed so bind-mounted
# upload directories get predictable ownership on the host.
RUN groupadd --gid 10001 wlwl \
 && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /app \
            --shell /usr/sbin/nologin wlwl

WORKDIR /app
COPY --from=build --chown=root:root /build/app.jar /app/app.jar

# images/upload.php is the only file-writing endpoint. In production
# params.fileLocation holds a Windows path (see application.yml), so it is
# unusable on Linux: this override wins before the DB lookup. /data/uploads is
# declared VOLUME below, so uploads survive a container re-create as long as the
# volume is named or the path is bind-mounted.
ENV WLWL_UPLOAD_DIR=/data/uploads \
    PORT=8080

# TZ matters for parity, not convenience: the PHP ran on a Windows/IIS box in
# Asia/Shanghai and writes MySQL DATETIME values in local time. The JVM reads TZ,
# so this keeps LocalDate/LocalDateTime results identical to production.
ENV TZ=Asia/Shanghai

# Heap-only tuning, container-aware sizing; it does not touch behaviour.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

RUN mkdir -p /data/uploads && chown -R wlwl:wlwl /data /app
VOLUME ["/data/uploads"]

USER wlwl

# Port from application.yml: server.port = ${PORT:8080}.
EXPOSE 8080

# Readiness without adding an endpoint: an unknown module is answered 404 by the
# gateway itself (BackendGateway), before any database access, and 404 is exactly
# what the PHP/IIS produced for a missing directory. So a 404 here means "the HTTP
# stack and dispatcher are up", and costs no DB connection and no log noise.
# No actuator is added on purpose: it would introduce a route surface the PHP
# never had, which is a parity question nobody asked for.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/backend/__healthcheck__" | grep -q 404

# shell form with exec so $JAVA_OPTS is expanded and the JVM stays PID 1
# (Spring Boot's shutdown hook then sees SIGTERM and drains correctly).
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

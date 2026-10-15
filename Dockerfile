# syntax=docker/dockerfile:1.7
# ── Stage 1: build ────────────────────────────────────────────────────────────
# The Maven image (not a bare JDK image) — the JDK images don't ship `mvn`.
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Cache the dependency layer separately from source
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline || true

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests \
 && cp target/crm-*.jar /build/app.jar

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Non-root user. Pre-create the local attachment-storage directory (used when
# Azure Blob isn't configured) owned by the app user, so a mounted named volume
# doesn't land root-owned and unwritable.
RUN addgroup -S crm && adduser -S crm -G crm \
    && mkdir -p /app/data/uploads && chown -R crm:crm /app
USER crm

COPY --from=builder --chown=crm:crm /build/app.jar app.jar

ENV LOCAL_UPLOAD_DIR=/app/data/uploads \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

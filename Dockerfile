# syntax=docker/dockerfile:1.7
# ── Stage 1: build with the Maven wrapper on JDK 21 ─────────────────────────
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# Wrapper + POM first so the dependency layer is cached independently of source.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
 && cp target/crm-*.jar /build/app.jar

# ── Stage 2: runtime (JRE 21, non-root) ─────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root user. Pre-create the local attachment-storage directory (used when
# Azure Blob isn't configured) owned by the app user, so a mounted named volume
# doesn't land root-owned and unwritable.
RUN groupadd --system crm && useradd --system --gid crm --home-dir /app --no-create-home crm \
    && mkdir -p /app/data/uploads && chown -R crm:crm /app \
    && apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
USER crm

COPY --from=builder --chown=crm:crm /build/app.jar app.jar

ENV LOCAL_UPLOAD_DIR=/app/data/uploads \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Uses the full JDK + Maven image. The Maven wrapper (mvnw) is used — not
# a system Maven — so there is zero dependency on what Maven version is
# installed on the host or inside this image beyond what the wrapper downloads.
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy wrapper metadata first. Docker caches this layer separately so that
# a pom.xml or source change does NOT re-download Maven itself.
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./
RUN chmod +x mvnw

# Download all dependencies into the image layer cache.
# Re-runs only when pom.xml changes — source edits skip this layer entirely.
RUN ./mvnw dependency:go-offline -q

# Copy source and build the fat JAR.
# -DskipTests is intentional and correct: integration tests (PostTransactionIntegrationTest,
# ConcurrencyIntegrationTest) require a live PostgreSQL instance. No database is available
# during `docker build`. Run tests separately via `./mvnw test` against the compose stack.
# See README § Running tests.
COPY src/ src/
RUN ./mvnw package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# JRE-only image — no compiler, no Maven, no source code.
# Results in an image ~120 MB lighter than the builder stage.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/ledger-api-0.1.0.jar app.jar

# Expose the port declared in application.yml / compose environment.
EXPOSE 9090

ENTRYPOINT ["java", "-jar", "app.jar"]

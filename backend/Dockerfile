# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------
# Pinned to an exact patch tag AND its multi-architecture index digest. The tag
# alone is mutable — Temurin republishes patch tags — so the digest is what
# actually makes this build reproducible. The digest is the top-level OCI index,
# verified to carry both linux/amd64 and linux/arm64, so this builds on CI and on
# an Apple Silicon laptop from the same line.
FROM eclipse-temurin:17.0.19_10-jdk-noble@sha256:f8857ccb2fe800666293d0da2526d162774e1314adfffb274fbadb877679d7a1 AS build

WORKDIR /workspace

# Dependency descriptors first, source second. Editing a Java file then leaves
# the dependency layer valid, which is the difference between a 4-second rebuild
# and a 4-minute one.
COPY --chmod=0755 mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./

# The cache mount keeps the Maven repository between builds without baking it
# into a layer. sharing=locked because two concurrent builds writing one local
# repo is how you get a corrupted artifact that takes an hour to diagnose.
RUN --mount=type=cache,id=pvcase-m2,target=/root/.m2,sharing=locked \
    ./mvnw -B -q dependency:go-offline

COPY src/ src/

# Tests run here on purpose. It costs a few seconds and makes it impossible to
# produce a runnable image from code that does not pass its own suite.
RUN --mount=type=cache,id=pvcase-m2,target=/root/.m2,sharing=locked \
    ./mvnw -B clean package

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
# JRE, not JDK: no compiler, no Maven cache, no build tooling in the shipped
# image. Verified that this image already ships /usr/bin/curl, so the compose
# healthcheck needs no apt-get layer — worth checking rather than assuming,
# since an unverified healthcheck that can never succeed is worse than none.
FROM eclipse-temurin:17.0.19_10-jre-noble@sha256:b1e6062e919b4fc402f46320960a64c2e832a0439c961af765061196b11b6a49

# Fixed numeric UID/GID. Numeric rather than a name so that `USER` still resolves
# to an unprivileged identity even if /etc/passwd is ever unavailable, and so
# host-side file ownership is predictable.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app \
      --no-create-home --home-dir /nonexistent \
      --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build --chown=10001:10001 --chmod=0444 /workspace/target/app.jar /app/app.jar

USER 10001:10001

EXPOSE 8080

# MaxRAMPercentage so the JVM sizes itself against the container limit rather
# than the host's total memory. ExitOnOutOfMemoryError so an OOM kills the
# process and lets the orchestrator restart it, instead of leaving a wedged JVM
# that still answers the healthcheck. UTC so log timestamps line up with the
# timestamps ops/backup.sh writes.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Duser.timezone=UTC", \
  "-jar", "/app/app.jar"]

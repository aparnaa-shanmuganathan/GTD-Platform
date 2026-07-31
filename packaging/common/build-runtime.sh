#!/usr/bin/env bash
# Phase 11 (packaging): produces a minimal custom Java 21 runtime image via jlink, containing
# only the JDK modules this application actually needs - not a full JDK, so the packaged
# installer never requires an end user to install Java separately (CLAUDE.md section 3).
#
# Must be run with JAVA_HOME pointing at a real JDK 21 install (the app targets Java 21 LTS -
# CLAUDE.md section 2 - independent of whatever JDK happens to be the default on the build
# machine). jlink can only link modules from the JDK whose jmods it's given, so this script's
# own JAVA_HOME's jmods directory is what determines the produced runtime's version.
#
# Usage: JAVA_HOME=/path/to/jdk-21 packaging/common/build-runtime.sh <path-to-fat-jar> <output-dir>
set -euo pipefail

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME must point at a JDK 21 installation (e.g. Homebrew's openjdk@21, Temurin 21, etc.)" >&2
  exit 1
fi

FAT_JAR="${1:?Usage: build-runtime.sh <path-to-fat-jar> <output-dir>}"
OUTPUT_DIR="${2:?Usage: build-runtime.sh <path-to-fat-jar> <output-dir>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

"$JAVA_HOME/bin/java" -version

echo "==> Extracting $FAT_JAR to inspect its module dependencies"
(cd "$WORK_DIR" && "$JAVA_HOME/bin/jar" xf "$FAT_JAR")

echo "==> Running jdeps to compute the minimal JDK module set"
DETECTED_MODULES=$("$JAVA_HOME/bin/jdeps" \
  --multi-release 21 \
  --ignore-missing-deps \
  --print-module-deps \
  --recursive \
  --class-path "$WORK_DIR/BOOT-INF/lib/*" \
  "$WORK_DIR/BOOT-INF/classes")

echo "jdeps detected: $DETECTED_MODULES"

# jdeps' static analysis misses reflection-only/service-loader-only module usage. These four are
# added defensively (all small) based on this platform's actual dependency set - Spring/Tomcat/
# Hibernate/HikariCP/Spring Security JMX and JNDI touch points (java.management, java.naming),
# java.util.logging bridging some libraries fall back to even though this app's own logging is
# SLF4J/Logback (java.logging), and elliptic-curve TLS cipher support (jdk.crypto.ec) in case a
# future admin enables HTTPS on the embedded Tomcat connector. Verified sufficient by actually
# launching the jlinked runtime and smoke-testing every workspace end-to-end (see
# docs/DEPLOYMENT.md) - not assumed correct from jdeps output alone.
EXTRA_MODULES="java.management,java.naming,java.logging,jdk.crypto.ec"

ALL_MODULES="${DETECTED_MODULES},${EXTRA_MODULES}"

rm -rf "$OUTPUT_DIR"
echo "==> Running jlink with modules: $ALL_MODULES"
"$JAVA_HOME/bin/jlink" \
  --add-modules "$ALL_MODULES" \
  --output "$OUTPUT_DIR" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=zip-6

echo "==> Runtime image written to $OUTPUT_DIR"
"$OUTPUT_DIR/bin/java" -version

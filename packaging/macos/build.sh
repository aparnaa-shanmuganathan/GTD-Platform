#!/usr/bin/env bash
# Phase 11 (packaging): builds a native macOS installer for the GTD Analytical Platform via
# jpackage, bundling: the jlink'd minimal Java 21 runtime (packaging/common/build-runtime.sh),
# the Spring Boot fat jar, and (via the Maven "package-macos-arm64"/"package-macos-amd64"
# profile already having been active during `mvn package`) a real Postgres 16 server for this
# Mac's architecture - see shared.bootstrap.EmbeddedPostgresEnvironmentPostProcessor for how
# that gets started automatically on first launch. No separate Postgres install, no JDK
# install - CLAUDE.md section 3's "nothing pre-installed except the OS itself" goal.
#
# Must be run ON a macOS machine (jpackage cannot cross-compile a .app/.dmg for macOS from
# another OS). Requires a real JDK 21 (JAVA_HOME) - see packaging/common/build-runtime.sh.
#
# Usage: JAVA_HOME=/path/to/jdk-21 packaging/macos/build.sh [dmg|pkg|app-image]
set -euo pipefail

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME must point at a JDK 21 installation" >&2
  exit 1
fi

INSTALLER_TYPE="${1:-dmg}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="$REPO_ROOT/packaging/build"
RUNTIME_DIR="$BUILD_DIR/runtime"
STAGING_DIR="$BUILD_DIR/macos-jar-input"
DIST_DIR="$BUILD_DIR/dist/macos"
FAT_JAR="$REPO_ROOT/target/gtd-platform.jar"
APP_NAME="GTD Platform"
# macOS requires an installer app-version whose first component is >= 1 (CFBundleVersion rules) -
# the Maven artifact version (0.1.0-SNAPSHOT) doesn't qualify, so the installer uses its own,
# separate version number. These are allowed to diverge; this is packaging metadata, not the
# application's own version.
APP_VERSION="1.0.0"

if [ ! -f "$FAT_JAR" ]; then
  echo "Fat jar not found at $FAT_JAR - run 'mvn -P package-macos-<arm64|amd64> clean package' first" >&2
  exit 1
fi

echo "==> Building the jlink runtime image"
"$REPO_ROOT/packaging/common/build-runtime.sh" "$FAT_JAR" "$RUNTIME_DIR"

echo "==> Staging jpackage input"
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR"
cp "$FAT_JAR" "$STAGING_DIR/"

echo "==> Running jpackage (type=$INSTALLER_TYPE)"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"
"$JAVA_HOME/bin/jpackage" \
  --type "$INSTALLER_TYPE" \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --vendor "GTD Platform Project" \
  --input "$STAGING_DIR" \
  --main-jar "gtd-platform.jar" \
  --runtime-image "$RUNTIME_DIR" \
  --java-options "-Dgtd.packaged=true" \
  --java-options "-Dspring.profiles.active=packaged" \
  --java-options "-Xmx2g" \
  --dest "$DIST_DIR"

echo "==> Installer written to $DIST_DIR"
ls -la "$DIST_DIR"

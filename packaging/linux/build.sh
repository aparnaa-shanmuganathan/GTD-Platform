#!/usr/bin/env bash
# Phase 11 (packaging): builds a native Linux installer (.deb or .rpm) via jpackage.
#
# NOT run or verified in this session's environment - this session runs macOS only, and
# jpackage cannot cross-compile a Linux installer from macOS (a real .deb/.rpm can only be
# produced by actually running this script on a Linux machine). Structurally identical to
# packaging/macos/build.sh (same jlink runtime step, same jpackage inputs); see
# docs/DEPLOYMENT.md for the exact commands to run this for real and what to check afterward.
#
# Prerequisites on the build machine:
#   - A real JDK 21 (JAVA_HOME) - e.g. Temurin 21 (https://adoptium.net), or your distro's
#     openjdk-21-jdk package.
#   - For --type deb: dpkg-deb (already present on any Debian/Ubuntu-family system).
#   - For --type rpm: rpmbuild (`sudo apt install rpm` on Debian-family, or native on
#     Fedora/RHEL-family).
#   - Before running: `mvn -P package-linux-amd64 clean package` (or package-linux-arm64 on an
#     arm64 build host) so the fat jar bundles the matching architecture's real Postgres 16
#     binaries (io.zonky.test.postgres:embedded-postgres-binaries-linux-{amd64,arm64v8}).
#
# Usage: JAVA_HOME=/path/to/jdk-21 packaging/linux/build.sh [deb|rpm]
set -euo pipefail

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME must point at a JDK 21 installation" >&2
  exit 1
fi

INSTALLER_TYPE="${1:-deb}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="$REPO_ROOT/packaging/build"
RUNTIME_DIR="$BUILD_DIR/runtime"
STAGING_DIR="$BUILD_DIR/linux-jar-input"
DIST_DIR="$BUILD_DIR/dist/linux"
FAT_JAR="$REPO_ROOT/target/gtd-platform.jar"
APP_NAME="gtd-platform"
APP_VERSION="1.0.0"

if [ ! -f "$FAT_JAR" ]; then
  echo "Fat jar not found at $FAT_JAR - run 'mvn -P package-linux-<amd64|arm64> clean package' first" >&2
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
  --linux-shortcut \
  --dest "$DIST_DIR"

echo "==> Installer written to $DIST_DIR"
ls -la "$DIST_DIR"

cat <<'EOF'

==> Manual verification checklist for this platform (not run in the macOS-only build session):
  1. Install the produced package (sudo dpkg -i dist/linux/*.deb   OR   sudo rpm -i dist/linux/*.rpm).
  2. Launch it from the applications menu, or directly: /opt/gtd-platform/bin/gtd-platform
  3. Confirm it starts with NO prior Postgres/JDK install on the machine, migrates its schema,
     and serves http://localhost:8080/ - the same checks packaging/macos/build.sh's own
     verification already proved for macOS.
  4. Confirm the bundled Postgres data directory lands under
     $XDG_DATA_HOME/gtd-platform/pgdata (or ~/.local/share/gtd-platform/pgdata if
     XDG_DATA_HOME is unset).
EOF

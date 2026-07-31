# Packaging (Phase 11)

Builds a native, OS-specific installer that bundles a custom Java 21 runtime (via `jlink`) and a
real PostgreSQL 16 server (via the same `io.zonky.test:embedded-postgres` binaries this project's
tests already use) — no separate Java or Postgres install needed on the target machine.

Full rationale, verification results, and the first-run/upgrade/uninstall runbook live in
[`docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md). This file is just the quick command reference.

| Target | Maven profile (`mvn -P <profile> clean package`) | Build script |
|---|---|---|
| macOS Apple Silicon | `package-macos-arm64` | `macos/build.sh [dmg\|pkg]` |
| macOS Intel | `package-macos-amd64` | `macos/build.sh [dmg\|pkg]` |
| Windows x64 | `package-windows-amd64` | `windows/build.ps1 -InstallerType [msi\|exe]` |
| Linux x64 | `package-linux-amd64` | `linux/build.sh [deb\|rpm]` |
| Linux arm64 | `package-linux-arm64` | `linux/build.sh [deb\|rpm]` |

Each build script must be run **on** its target OS (jpackage cannot cross-compile a different
OS's installer) with `JAVA_HOME` pointing at a real JDK 21. Only the macOS path has actually been
built and verified end-to-end so far — see `docs/DEPLOYMENT.md` §6 for what's still needed on
Windows/Linux.

`packaging/build/` (jlink runtime images, staged jars, built installers) is gitignored — entirely
reproducible output, never committed.

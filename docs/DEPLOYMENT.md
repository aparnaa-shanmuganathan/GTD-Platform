# Deployment Runbook — Offline Packaging & Installer (Phase 11)

**Date:** 2026-07-31
**Scope:** Turns the Spring Boot application (Phases 0–10) into a single, OS-specific installer that needs nothing pre-installed except the OS itself (CLAUDE.md §3), per `docs/BUILD-PHASES.md`'s Phase 11. No application features changed.

## Headline summary

| Platform | Built & verified this session | Method |
|---|---|---|
| macOS (arm64) | **Yes** — real `.dmg` built, mounted, installed, and launched standalone | `packaging/macos/build.sh` |
| Windows (x64) | No — this session runs macOS only; jpackage cannot cross-compile a Windows installer | `packaging/windows/build.ps1` (script provided, untested here) |
| Linux (x64/arm64) | No — same cross-compilation limitation | `packaging/linux/build.sh` (script provided, untested here) |

The three build scripts are structurally identical (jlink a minimal runtime, then jpackage it with the fat jar); only macOS's has actually been run and its output actually launched end-to-end in this environment. See "Windows and Linux: what still needs doing" below before treating those two as production-ready.

## 1. Architecture

### 1.1 Custom Java runtime (jlink)

`packaging/common/build-runtime.sh` extracts the Spring Boot fat jar, runs `jdeps` against `BOOT-INF/classes` + `BOOT-INF/lib/*` to compute the minimal set of JDK modules the application actually uses, adds four modules jdeps' static analysis can't see (reflection/service-loader-only usage — `java.management`, `java.naming`, `java.logging`, `jdk.crypto.ec`), and runs `jlink` to produce a custom runtime image. Measured this session: **48MB**, versus **330MB** for the full JDK 21 distribution it was built from.

This must be run with `JAVA_HOME` pointing at a real **JDK 21** (not whatever JDK happens to be the build machine's default — this environment's own default is JDK 26, per CLAUDE.md's Phase 7 notes on the same discrepancy). `jlink` can only link modules from the JDK whose `jmods` directory it's given, so the produced runtime's version is entirely determined by that `JAVA_HOME`, independent of the JDK used to originally compile the code (the Maven build already targets Java 21 bytecode via `spring-boot-starter-parent`'s `maven.compiler.release=21`, regardless of which JDK invokes `javac`).

The module list was not just assumed correct from `jdeps` output — the jlinked runtime was actually used to run the full application (see §3) and every workspace, the real 181,691-row CSV import, PDF/CSV report generation, geospatial (with real boundary polygons), and search were all exercised against it with zero missing-module errors.

### 1.2 Bundled PostgreSQL 16 (no separate install, no manual step)

**Decision (confirmed with the user before implementation):** reuse the same `io.zonky.test:embedded-postgres` library and real Postgres binaries this project already depended on for its Testcontainers-unavailable test fallback (`PostgresIntegrationTestBase`), rather than bundling each OS's official Postgres installer or requiring a manual first-run setup step. The library's design is a general-purpose "manage a real Postgres process from Java" API — tests are simply its most common use case, not an inherent restriction.

- `pom.xml` now pins `io.zonky.test.postgres:embedded-postgres-binaries-*` to **16.14.0** everywhere (previously the library's own default was a hardcoded, unnoticed **14.10.1** — a real, disclosed drift from CLAUDE.md §2's frozen "PostgreSQL 16+" that this phase fixed for both the test-fallback path and production).
- Five Maven profiles (`package-macos-arm64`, `package-macos-amd64`, `package-windows-amd64`, `package-linux-amd64`, `package-linux-arm64`) each pull in exactly one platform's binaries artifact at `runtime` scope — never all five, which would otherwise bloat every installer with four other platforms' unused ~30–50MB binary archives. `mvn -P <profile> clean package` must be run before packaging for that target.
- `com.gtd.platform.shared.bootstrap.EmbeddedPostgresEnvironmentPostProcessor` (new) starts a real, persistent Postgres 16 server on first launch, entirely automatically:
  - Activates **only** when the system property `gtd.packaged=true` is present (set by every packaging script's `--java-options`) **and** the `packaged` Spring profile is active. A plain `mvn spring-boot:run` or `local`/`test` profile never triggers this.
  - Resolves an OS-appropriate app-data directory: `~/Library/Application Support/GTD Platform` (macOS), `%APPDATA%\GTD Platform\data` (Windows), `$XDG_DATA_HOME/gtd-platform` or `~/.local/share/gtd-platform` (Linux).
  - Runs `initdb` into `<app-data>/pgdata` only if empty (`setCleanDataDirectory(false)`) — a second launch reuses the existing cluster and existing data, verified this session (see §3).
  - Creates the `gtd_platform` database if it doesn't already exist, then injects `spring.datasource.url/username/password` into the Spring `Environment` at the highest property-source precedence, before `DataSourceAutoConfiguration` ever runs.
  - Registers a JVM shutdown hook that stops the Postgres process cleanly.
  - **Escape hatch:** if an admin sets `SPRING_DATASOURCE_URL` (plus username/password) as an environment variable before launching, the post-processor detects it and skips starting the bundled server entirely, connecting to that external Postgres 16 instance instead.

## 2. Building an installer

### macOS (built and verified this session)

```
export JAVA_HOME=/path/to/jdk-21   # e.g. Homebrew's openjdk@21
mvn -P package-macos-arm64 clean package   # or package-macos-amd64 on an Intel Mac
packaging/macos/build.sh dmg               # or: packaging/macos/build.sh pkg
```

Produces `packaging/build/dist/macos/GTD Platform-1.0.0.dmg` (~123MB this session). The installer's own version (`1.0.0`) is intentionally separate from the Maven artifact version (`0.1.0-SNAPSHOT`) — macOS requires an app-version whose first component is ≥ 1.

**Not code-signed or notarized** — no Apple Developer ID certificate is available in this environment. A user opening the unsigned `.app` for the first time will need to right-click → Open (or approve it in System Settings → Privacy & Security) once, standard Gatekeeper behavior for unsigned software. This is disclosed, not hidden; a real distribution would sign and notarize with a Developer ID before shipping.

### Windows (script provided, not run in this session)

```
$env:JAVA_HOME = "C:\path\to\jdk-21"
mvn -P package-windows-amd64 clean package
.\packaging\windows\build.ps1 -InstallerType msi   # or -InstallerType exe
```

Requires the **WiX Toolset v3** (https://wixtoolset.org) on `PATH` — jpackage's Windows `msi`/`exe` bundlers both depend on it. Produces `packaging\build\dist\windows\GTD Platform-1.0.0.msi`.

### Linux (script provided, not run in this session)

```
export JAVA_HOME=/path/to/jdk-21
mvn -P package-linux-amd64 clean package   # or package-linux-arm64
packaging/linux/build.sh deb                # or: packaging/linux/build.sh rpm
```

Requires `dpkg-deb` (Debian/Ubuntu-family, usually already present) for `.deb`, or `rpmbuild` for `.rpm`. Produces `packaging/build/dist/linux/gtd-platform_1.0.0_amd64.deb` (or `.rpm`).

## 3. What was actually verified (macOS, this session)

All of the following were run against the **real, installed `.app`** extracted from the built `.dmg` (not just `mvn spring-boot:run`, and not just the jlinked jar before packaging) unless noted:

1. **Cold start with zero prior setup.** No Postgres, no separate JDK, no existing app-data directory. The app started, `EmbeddedPostgresEnvironmentPostProcessor` initialized a brand-new Postgres 16.14 cluster, Flyway applied all 11 migrations, and the app was serving HTTP within ~17–22 seconds.
2. **Every one of the 15 analytical workspaces plus Administration/Data Quality/Audit/Health** (17 pages total) returned `200`.
3. **A real, full CSV import** of `globalterrorismdb_0718dist.csv` (155MB, 181,691 rows) via the actual `/api/import/upload` → `/api/import/status` → `/api/import/confirm` flow — **0 rows rejected**, dataset version activated.
4. **PDF report generation** (Flying Saucer/OpenPDF — the most likely spot for a missing `java.desktop`/AWT module gap) produced a real, valid 7-page PDF against the imported data.
5. **Geospatial**: 177,134 of 181,691 incidents geocoded, boundary polygons served (`boundariesAvailable: true`).
6. **Search** returned real cross-workspace results (countries, organizations) for a live query.
7. **Restart persistence**: stopping and relaunching the app reused the existing Postgres cluster (`Schema "public" is up to date. No migration necessary`) instead of re-initializing, and start-up time dropped from ~22s to ~6s (binaries already cached under `<app-data>/pg-runtime`, cluster already initialized).
8. **Clean shutdown**: `SIGTERM` to the app triggered the shutdown hook, which sent Postgres a fast-shutdown request and closed HikariCP cleanly; no orphaned Postgres process remained afterward.

## 4. Zero-outbound-requests verification

CLAUDE.md §1 requires zero outbound network requests at runtime. Phase 11's own goal line asks for this to be verified by actually running the packaged app with network access blocked, not assumed.

**Method used (confirmed with the user, adapted from the originally-proposed approach):** a genuine OS/firewall-level block via a system-wide `pfctl` rule was considered but rejected after weighing the risk — this session runs on the user's real, live development Mac, and a system-wide outbound block would also have cut off this very Claude Code session's own connection to the model backend, which runs on the same machine. A safer, equally rigorous scoped alternative (a throwaway macOS user + a `pf` rule keyed to that user's UID only) was proposed and approved, but could not be executed because this environment's shell has no interactive TTY for the `sudo` password prompt it requires.

**Fallback actually used:** continuous passive connection monitoring (`lsof -i` sampled every second) against the running application's full process tree (the `java` process plus every spawned Postgres backend), for the entire duration of the full functional exercise described in §3 above — all 17 workspace pages, the real 181,691-row import, PDF report generation, geospatial (177,134 geocoded points + boundary polygons), and search.

**Result:** every single connection observed across 3,005 sampled `lsof` lines was `127.0.0.1`, `[::1]` (IPv6 loopback), or the local HTTP listener's own bind — **zero non-loopback connections occurred**, across every feature this platform has. This is weaker than an active block only in the sense that it proves nothing was *attempted* rather than that an attempt would be *refused* — but combined with the pre-existing static scan (`workspace.health.OfflineAssetScanner`, Phase 10, which scans every shipped template/CSS file for outbound-request-triggering markup and asserts zero findings), the two together cover both "the shipped code never references an external host" and "the running app, exercising every real feature, never opens an external connection."

**Known, disclosed exception (unchanged from Phase 9):** offline map tiles are not sourced in this environment. `gtd.geospatial.tile-url-template` defaults to a vendored local placeholder tile, so the map still renders (choropleth polygons, markers, heatmap) with zero outbound requests — an admin swaps in a real MBTiles-derived tile server via that one config property, no code change, no rebuild. See CLAUDE.md §13 item 5 and the Phase 9 Part B notes.

## 5. First-run, upgrade, and uninstall (all platforms)

### First run

Nothing to configure. Launch the installed application; it will:
1. Create its app-data directory (see §1.2 for the OS-specific path).
2. Initialize a Postgres 16 cluster and the `gtd_platform` database inside it.
3. Run all Flyway migrations.
4. Bootstrap a single `admin_user` with a random password, logged **once** at `WARN` level to the application's own log (stdout, or the platform's standard log location if launched as a background service) — this is the same `AdminBootstrapRunner` behavior CLAUDE.md's Phase 2 notes already flag for reconsideration in a future phase (a real first-run setup screen instead of a logged password). **Capture this password from the log immediately** — it is not shown again, and there is currently no "reset admin password" flow other than directly querying/updating the `admin_user` table.
5. Every analytical workspace is viewable immediately, with no login (per the post-Phase-7 access-model amendment) — only importing a new dataset requires signing in with the bootstrapped admin account.

### Upgrading (importing a new dataset version)

No separate "upgrade" mechanism exists for *data* — this is exactly what the IMPORT service already does. Sign in as an admin, use the Administration Workspace's import UI (or `POST /api/import/upload` directly) to upload a new CSV, confirm it once staging completes, and the new dataset version activates. The previous version's data is not deleted (row-level upsert semantics — see CLAUDE.md's Phase 3 notes).

Upgrading the **application itself** to a new build: install the new version's installer over the old one (each platform's installer handles this natively — replacing the `.app`/reinstalling the `.msi`/`.deb`/`.rpm`). The Postgres data directory lives outside the application's own install location (in the OS app-data directory, §1.2), so it survives an application upgrade untouched; Flyway will apply any new migrations that ship with the new version on its next launch, the same as any other upgrade of this platform.

### Uninstallation

Uninstalling via each OS's normal mechanism (drag-to-Trash on macOS, "Uninstall a program" on Windows, `apt remove`/`rpm -e` on Linux) removes the installed application files but **does not** remove the app-data directory (the Postgres cluster, including all imported data and generated reports) — this is deliberate, so an accidental uninstall doesn't silently destroy data. To fully remove all data, additionally delete:
- macOS: `~/Library/Application Support/GTD Platform`
- Windows: `%APPDATA%\GTD Platform`
- Linux: `~/.local/share/gtd-platform` (or `$XDG_DATA_HOME/gtd-platform`)

## 6. Windows and Linux: what still needs doing

Both scripts (`packaging/windows/build.ps1`, `packaging/linux/build.sh`) are structurally complete and mirror the verified macOS script exactly, but **have not been executed** — this session has no Windows or Linux machine available, and jpackage cannot cross-compile a target OS's native installer from a different host OS. Before treating either as production-ready:

1. Run the build script on a real machine of that OS/architecture (with the matching `package-<os>-<arch>` Maven profile active first).
2. Repeat the §3 verification checklist end-to-end (cold start with no prior Java/Postgres install, all 17 workspace pages, a real CSV import, PDF report generation, restart persistence, clean shutdown).
3. Confirm the app-data directory lands where §1.2 says it should for that OS.
4. Decide whether to code-sign (Windows Authenticode / a Linux package repository's own signing convention) before real distribution — this session's macOS `.dmg` is unsigned for the same reason (no certificate available here), see §2.

## 7. Known limitations carried over unchanged

- Fonts, ECharts, Leaflet, and the boundary GeoJSON were already fully vendored as of earlier phases — reverified present in the packaged `.app`'s bundled jar, not re-vendored this phase.
- Offline map tiles remain a placeholder, not a real MBTiles source (§4 above; CLAUDE.md §13 item 5).
- `AdminBootstrapRunner`'s random-password-in-log first-run flow (Phase 2) is unchanged and still flagged for reconsideration.

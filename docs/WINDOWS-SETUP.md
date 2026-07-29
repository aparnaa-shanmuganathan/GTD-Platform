# Cross-Platform Setup — Windows (and keeping macOS working)

This project already targets Windows, Linux, and macOS (`CLAUDE.md` §3). The codebase itself needed **no changes** to run on Windows — it was audited specifically for this: no hardcoded `/Users/...` paths, no shell-specific assumptions in application code, all file handling goes through `java.nio.file.Path`/`Files.createTempFile` (both OS-portable), and datasource credentials are read from environment variables, never hardcoded. What differs between machines is entirely **environment setup and a couple of shell-syntax differences**, covered below.

## 1. Prerequisites on the Windows machine

Install, matching what's already running on the Mac:

- **JDK 21 (LTS)** — e.g. Eclipse Temurin 21 or Microsoft Build of OpenJDK 21. After installing, set `JAVA_HOME` (System Properties → Environment Variables, or `setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21.x.x"`) and add `%JAVA_HOME%\bin` to `PATH`.
- **Maven 3.9+** — download the binary zip, extract it, add its `bin` folder to `PATH`. (See §5 below for a way to avoid needing this step at all.)
- **PostgreSQL 16** — the EnterpriseDB Windows installer is simplest; it also installs `psql` and pgAdmin.
- **Git for Windows** — needed either way; also gives you a bash-compatible shell (Git Bash) if you'd rather not deal with `cmd`/PowerShell syntax differences at all.
- **Claude Code CLI** — install per Anthropic's current Claude Code documentation for Windows (the install method may have changed since this was written — check docs.claude.com if the command you have doesn't work).
- **Docker Desktop (optional)** — only if you want integration tests to use Testcontainers instead of the automatic `embedded-postgres` fallback. Not required either way; `PostgresIntegrationTestBase` picks whichever is available.

## 2. Bringing the project over

The project is already a git repository with one baseline commit (Phases 0–3 complete, Phase 4 in progress). The simplest way to move it: **copy the whole `GTD-Platform` folder** (hidden `.git` directory included) to the Windows machine via whatever transfer method you'd already use — external drive, a shared cloud folder (OneDrive/Dropbox/Google Drive), or a LAN transfer. A plain folder copy carries the full git history with it; there's no server-side remote required for this.

Two things deliberately do **not** travel with that copy, both by design (see `.gitignore`):

- `target/` and `logs/` — build output and logs, regenerated automatically the first time you run `mvn clean verify` on Windows. Fine to leave behind or delete before copying.
- `.claude/settings.local.json` — machine-local Claude Code permissions (and, as found during this audit, a throwaway local dev DB password that should never be shared across machines anyway). Claude Code will regenerate this fresh on Windows as you approve commands there.

**Not in git at all, and not on disk yet either:** the real GTD dataset (`globalterrorismdb_0718dist.csv`, ~155MB). It's licensed data, deliberately excluded from version control (`/data/` in `.gitignore`). Wherever you currently have that file, copy it directly to `GTD-Platform/data/globalterrorismdb_0718dist.csv` on whichever machine you're testing the import pipeline from — Mac, Windows, or both if you want to validate on each.

## 3. One-time local database setup (same SQL on both OSes)

Identical to the Mac instructions in the main `README.md` — connect with `psql` or pgAdmin and run:

```sql
CREATE ROLE gtd_app WITH LOGIN PASSWORD 'change-me';
CREATE DATABASE gtd_platform OWNER gtd_app;
```

Use a different password than whatever was used on the Mac's local dev database — don't reuse it across machines.

## 4. Environment variables — the one real shell difference

The app reads `GTD_DB_USERNAME` / `GTD_DB_PASSWORD` from the environment (never hardcoded). How you set them differs by shell:

| Shell | Command |
|---|---|
| macOS/Linux (bash/zsh) | `export GTD_DB_USERNAME=gtd_app` |
| Windows PowerShell (current session only) | `$Env:GTD_DB_USERNAME = "gtd_app"` |
| Windows PowerShell (persists across sessions) | `setx GTD_DB_USERNAME "gtd_app"` (open a new terminal afterward — `setx` doesn't affect the current one) |
| Windows Git Bash | `export GTD_DB_USERNAME=gtd_app` (same as macOS/Linux — this is why Git Bash is a convenient option on Windows) |

Same pattern for `SPRING_PROFILES_ACTIVE=local` and, if you use it, `GTD_REAL_CSV_PATH`.

## 5. Optional but recommended: generate the Maven Wrapper

The project currently relies on a system-wide Maven install on whichever machine builds it. Adding the Maven Wrapper (`mvnw` / `mvnw.cmd`) pins the exact Maven version and means a Windows machine doesn't need Maven installed separately at all — `mvnw.cmd` bootstraps it on first run.

This wasn't generated as part of this migration because it requires a live connection to Maven Central to fetch the wrapper distribution, which isn't available in this session's sandbox. Run this once, from either machine, whichever has Maven + internet already (the Mac does):

```bash
cd GTD-Platform
mvn -N wrapper:wrapper -Dmaven=3.9.9
git add mvnw mvnw.cmd .mvn
git commit -m "chore: add Maven Wrapper for reproducible builds across machines"
```

After that, every command in this doc and in `docs/BUILD-PHASES.md` that says `mvn ...` can be run as `./mvnw ...` (macOS/Linux/Git Bash) or `mvnw.cmd ...` (Windows `cmd`/PowerShell) instead, with zero difference in behavior.

## 6. Verifying the migration worked

On the Windows machine, after prerequisites are installed and the folder is copied over:

```bash
cd GTD-Platform
mvn clean verify
```

This does **not** need `GTD_DB_USERNAME`/`GTD_DB_PASSWORD` or a running PostgreSQL — the `test` profile provisions its own throwaway database (Testcontainers or `embedded-postgres`, whichever is available). If this passes, the build itself is confirmed portable.

Then, to actually run the app against the local database from §3:

```bash
# set GTD_DB_USERNAME / GTD_DB_PASSWORD per §4 first
$Env:SPRING_PROFILES_ACTIVE = "local"     # PowerShell
mvn spring-boot:run
```

Visit `http://localhost:8080/actuator/health` (should return `{"status":"UP"}`) and `http://localhost:8080/login`. Check the console/log for the bootstrapped admin password on first run (see main `README.md`).

If you placed the real CSV per §2, also run:

```bash
mvn test -Dtest=RealFileImportPerformanceTest
```

to get a real ≤5-minute NFR measurement on the new machine — worth doing on both Mac and Windows if you plan to keep developing on both, since the reference hardware baseline in `CLAUDE.md` §10 (8-core/2.5GHz+/16GB RAM/SSD) may not be met identically by both machines.

## 7. Going forward on two machines

- Keep committing after every phase, on whichever machine you're actively working on (per `docs/BUILD-PHASES.md`'s "commit after each phase passes" rule).
- Sync the two machines' git history the same way you moved the project initially — copy the folder, or set up a proper git remote (GitHub/GitLab/self-hosted/etc.) once you want push/pull instead of manual copies. This document doesn't set one up, since that requires creating an account, which is yours to do.
- `.gitattributes` (added during this migration) normalizes line endings automatically, so switching between the two machines won't produce noisy whitespace-only diffs.

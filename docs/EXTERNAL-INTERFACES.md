# External Interface Requirements (Phase 12 — SRS Section 5 closeout)

**Scope:** every point where this platform crosses its own process boundary to the outside world. Per CLAUDE.md §1's zero-outbound-requests requirement and §2's frozen stack, that boundary is deliberately narrow: **the local filesystem, and a local-only PostgreSQL server** — no network APIs, no external services, no third-party integrations of any kind. This document enumerates both precisely, plus the smaller filesystem touchpoints Phases 9–11 added along the way.

## 1. Database interface: PostgreSQL 16

The platform's only stateful external interface. Two distinct deployment shapes exist, selected by Spring profile — the interface contract (JDBC, schema, isolation) is identical either way; only how the server is provisioned differs.

| | `local`/`test` profile (development) | `packaged` profile (Phase 11 installer) |
|---|---|---|
| Provisioning | An admin-installed PostgreSQL 16+ instance, connection details supplied via `GTD_DB_USERNAME`/`GTD_DB_PASSWORD` env vars (`application-local.yml`) | A bundled Postgres 16.14 server, started and stopped automatically by `shared.bootstrap.EmbeddedPostgresEnvironmentPostProcessor` |
| Host/port | `localhost:5432` (fixed) | `localhost:<ephemeral, JVM-allocated at each start>` — never fixed, since only this same JVM ever needs to know it |
| Database name | `gtd_platform` | `gtd_platform` (created automatically if absent) |
| Credentials | Admin-supplied, external to the app | `postgres`/`postgres`, `trust`-authenticated, loopback-only (Zonky `EmbeddedPostgres` default) |
| Data directory | Wherever the admin's own Postgres install keeps it | OS-specific app-data path — see `docs/DEPLOYMENT.md` §1.2 (macOS: `~/Library/Application Support/GTD Platform/pgdata`; Windows: `%APPDATA%\GTD Platform\data\pgdata`; Linux: `~/.local/share/gtd-platform/pgdata`) |
| Escape hatch | N/A (already externally provisioned) | Setting `SPRING_DATASOURCE_URL` (+ username/password) as an environment variable before launch skips the bundled server entirely and connects to an admin-specified external Postgres 16 instead |

**Protocol:** standard PostgreSQL wire protocol over TCP, via the `org.postgresql:postgresql` JDBC driver, pooled through HikariCP. **Schema ownership:** Flyway (`src/main/resources/db/migration`, `V1`–`V11` as of Phase 10) is the sole schema-migration mechanism — the application never issues DDL outside a versioned migration. **Isolation:** `READ COMMITTED` (JPA/Hibernate default) for ordinary workspace queries; `REPEATABLE READ` specifically for REPORT's own content-gathering transaction (CLAUDE.md §8); a single PostgreSQL advisory lock serializes IMPORT (one import at a time, independent of REPORT's isolation).

**Never exposed externally beyond loopback** — in both deployment shapes, nothing binds the Postgres port to a non-loopback interface, and no firewall exception or port-forwarding is ever configured by this platform.

## 2. Filesystem interfaces

### 2.1 CSV import (the platform's primary data-ingestion interface)

| Aspect | Detail |
|---|---|
| Entry point | `POST /api/import/upload`, `multipart/form-data`, field name `file` |
| Accepted format | `.csv` extension required (`ERR-IMPORT-001` otherwise); parsed via Apache Commons CSV |
| Encoding | **Windows-1252**, not UTF-8 — a real, hard-won Phase 7 finding (`CsvStagingLoader.GTD_CSV_CHARSET`): the real distributed GTD file's free-text columns (`summary`/`scite*`/`motive`) are not valid UTF-8 |
| Size limits | 250MB per request (`spring.servlet.multipart.max-file-size`/`max-request-size`), comfortably above the real file's 155MB; `file-size-threshold: 0` forces every upload straight to a temp file on disk, never buffered in heap |
| Handling | The servlet container's own transient multipart temp file is immediately copied to a stable path (`Files.createTempFile("gtd-import-", ".csv")`) before the upload request returns, since `ImportJobRunner`'s background job (AWS-IMPORT-001) reads it long after the original request completes |
| Column contract | ~130 TEXT columns matching the real GTD Codebook header exactly (verified against the actual file in Phase 7 — Phase 1's reconstruction was already column-perfect; only the encoding was wrong) |
| Write path | Direct JDBC (`JdbcTemplate`, no ORM) into `staging_incident`, then normalized into `fact_incident`/dimension tables — see CLAUDE.md §7/§12 Phase 1 & 3 notes |

This is the one interface where **externally-supplied data** enters the platform at all. Every other interface below is either internal-to-the-install (config files, vendored assets) or admin-supplied deployment configuration, not end-user data.

### 2.2 Generated report storage (REPORT service, Phase 6)

| Aspect | Detail |
|---|---|
| Location | `${user.home}/.gtd-platform/reports` (`gtd.reports.directory`, `application.yml`) — deliberately the OS user's home directory, never a temp directory, since `generated_report` rows are immutable and permanent (CLAUDE.md §7) and the files they reference must be equally durable |
| Format | PDF (Flying Saucer/OpenPDF-rendered) or CSV, one file per generated report |
| Access | `POST /api/report/download` streams the file's raw bytes back (the one other endpoint, alongside login/logout, exempted from the standard JSON envelope — CLAUDE.md §9/§12 Phase 6) |
| Failure mode | A failed render/store still persists a `FAILED` `generated_report` row with no `file_path`, so a failed attempt stays visible in history instead of vanishing (Phase 6 decision) |

### 2.3 Admin-configurable geospatial resources (Phase 9)

Two properties under `gtd.geospatial.*` (`application.yml`) point at local filesystem/classpath resources an admin can swap without a rebuild:

| Property | Default | Purpose |
|---|---|---|
| `tile-url-template` | `/img/map-placeholder-tile.png` (vendored classpath resource) | Map tile source. A real offline deployment would point this at a locally-served MBTiles-derived tile server's URL template instead — genuinely unsourced in this environment (CLAUDE.md §13 item 5), not faked |
| `boundary-geojson-path` | `/geo/world-countries.geojson` (vendored classpath resource, Natural Earth 1:110m, public domain) | Choropleth boundary polygons. A leading `/` resolves as a classpath resource under `static/`; any other value resolves as an absolute filesystem path, so an admin can drop in a richer file (e.g. 1:50m) without a rebuild |
| `boundary-name-property` | `NAME,ADMIN,NAME_LONG,NAME_EN,SOVEREIGNT,FORMAL_EN,BRK_NAME` | Not a file path — a comma-separated list of GeoJSON feature properties tried in order when matching a polygon to a `dim_country` row |

`GET /api/geospatial/boundaries` streams whichever file is currently configured, raw, as `application/geo+json` — a third documented exemption from the standard envelope (alongside login/logout and report download).

### 2.4 Vendored static assets (not admin-configurable, bundled at build time)

Fonts (IBM Plex, 7 woff2 files), Apache ECharts (~1MB), Leaflet 1.9.4 + three plugins (marker clustering, heatmap, drawn-region tool), and the Natural Earth boundary file (§2.3) are all committed under `src/main/resources/static/`, served by Spring's own static-resource handling — no separate file-serving interface, no CDN, ever (CLAUDE.md §1/§2).

## 3. What this platform does NOT interface with

Explicitly, per CLAUDE.md §11 and §1: no live terrorism-feed API, no cloud service, no external authentication provider, no outbound HTTP/HTTPS client of any kind in production code (Phase 10's `OfflineAssetScanner` and Phase 11's live connection-monitoring verification both confirm this empirically, not just by omission from this list). The only two external interfaces this platform has, full stop, are §1 (Postgres) and §2 (filesystem) above.

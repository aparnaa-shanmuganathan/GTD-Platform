# GTD Analytical Platform

A fully offline (air-gapped-compatible) Business Intelligence and Decision Support Platform built on the Global Terrorism Database (GTD). See `CLAUDE.md` at the project root for the full architectural spec and `docs/BUILD-PHASES.md` for the phased build plan.

Phases 0-3 are done: project foundation, the full database schema (Flyway migrations), session-based auth (login/logout, audit logging), and the IMPORT service (CSV upload → staging → normalization → confirm/activate). See `docs/BUILD-PHASES.md` for what's next.

## Prerequisites

- **JDK 21** (LTS)
- **Maven 3.9+**
- **PostgreSQL 16** (for running the app locally against the `local` profile)
- **Docker** (optional) — if available, integration tests use a real Testcontainers-managed PostgreSQL 16 container. If Docker is not available, tests automatically fall back to `io.zonky.test:embedded-postgres`, which runs a real (Docker-free) Postgres binary instead. Either way, tests run against genuine PostgreSQL, never H2.

This project is developed cross-platform (Windows, Linux, macOS — `CLAUDE.md` §3) and has been audited to contain no OS-specific paths or shell assumptions in application code. **If you're setting this up on Windows, or moving between Windows and macOS/Linux, see `docs/WINDOWS-SETUP.md`** for prerequisite install notes and the couple of shell-syntax differences (mainly environment variable syntax).

## One-time local database setup

The `local` Spring profile expects a PostgreSQL 16 instance on `localhost:5432` with a database named `gtd_platform`. Credentials are **never hardcoded** — they're read from environment variables.

1. Create the database and a role for the app (adjust the password):

   ```sql
   CREATE ROLE gtd_app WITH LOGIN PASSWORD 'change-me';
   CREATE DATABASE gtd_platform OWNER gtd_app;
   ```

2. Export the credentials before running the app (or put them in a local `.env` file that you `source` — `.env` is gitignored):

   ```bash
   export GTD_DB_USERNAME=gtd_app
   export GTD_DB_PASSWORD=change-me
   ```

Flyway applies the full schema automatically on first startup. On an empty `admin_user` table, the app also bootstraps one admin account and logs its randomly-generated password once, at `WARN`, to the console/log file — watch for a line like:

```
No admin_user found - bootstrapped a default account. username='admin' password='...'
```

That's your login for `http://localhost:8080/login`. It's generated fresh per install (not a fixed default), and is only ever shown this once.

## Building and testing

```bash
mvn clean verify
```

This compiles the app, runs the unit/integration test suite (including the smoke test that boots the full Spring context against a real Postgres instance — Testcontainers if Docker is running, `embedded-postgres` otherwise), and packages the jar. `mvn clean verify` does **not** require `GTD_DB_USERNAME`/`GTD_DB_PASSWORD` or a locally running PostgreSQL — the `test` profile provisions its own throwaway database automatically.

## Running the app locally

With the local database set up and credentials exported:

```bash
export SPRING_PROFILES_ACTIVE=local
mvn spring-boot:run
```

The app starts on `http://localhost:8080`. Health check: `http://localhost:8080/actuator/health`.

If `GTD_DB_USERNAME`/`GTD_DB_PASSWORD` aren't set, or PostgreSQL isn't reachable at `localhost:5432`, startup will fail fast with a clear datasource connection error rather than crash-looping silently.

## Project layout

Package structure mirrors the layered architecture frozen in `CLAUDE.md` section 4:

```
com.gtd.platform
├── workspace.{executive,country,region,organization,attack,weapon,
│              target,casualty,statistical,geospatial,report,
│              administration,dataquality,audit,health}
├── service.{imports,search,insight,config}
└── shared.{auth,logging,exception,reporting,db,security,validation,file}
```

`service.imports` (IMPORT) and `shared.auth`/`shared.logging`/`shared.exception`/`shared.api`/`shared.db`/`shared.reporting` have real implementations now; the 15 `workspace.*` packages and the other 3 cross-cutting services are still empty (`package-info.java` only). Workspace packages never depend on one another directly; shared behavior goes through `shared.*` or one of the cross-cutting `service.*` packages.

## Configuration profiles

- **`local`** (`application-local.yml`) — connects to a local PostgreSQL 16 instance; credentials from `GTD_DB_USERNAME` / `GTD_DB_PASSWORD` environment variables.
- **`test`** (`application-test.yml`) — used by integration tests; datasource connection details are provisioned dynamically at test-startup time (see `com.gtd.platform.testsupport.PostgresIntegrationTestBase`), not from this file.

## Logging

Logback (`src/main/resources/logback-spring.xml`) writes to the console and to a size/time-based rolling file under `logs/` (gitignored). There is no external log shipping — this is an air-gapped platform.

## Importing the real GTD dataset

The IMPORT service (`service.imports`, Phase 3) uploads a CSV, stages it raw, validates/normalizes it into the dimension and fact tables, then waits for an explicit confirm step to activate the resulting `dataset_version` and refresh the materialized views. Endpoints (all POST, all authenticated, standard envelope): `POST /api/import/upload` (multipart `file` + `mode` of `APPEND`/`REPLACE`), `POST /api/import/status` (poll `{"importBatchId": N}`), `POST /api/import/confirm`.

The real file (`globalterrorismdb_0718dist.csv`, ~155MB/~181,691 rows, sourced from Kaggle's START-UMD/gtd dataset) is **not committed to this repo** (large, licensed data — see `.gitignore`). To run the real-file performance test (`RealFileImportPerformanceTest`, which asserts the ≤5 min NFR from `CLAUDE.md` section 10 and otherwise skips itself with a clear message):

```bash
mkdir -p data
# place globalterrorismdb_0718dist.csv in ./data/, or point elsewhere:
export GTD_REAL_CSV_PATH=/path/to/globalterrorismdb_0718dist.csv

mvn test -Dtest=RealFileImportPerformanceTest
```

This test has not yet been run against the real file in this environment — CLAUDE.md section 12 flags this explicitly rather than claiming a number that was never measured.

## Test database strategy

See `CLAUDE.md` sections 2 and 12 for the frozen decision: Testcontainers PostgreSQL 16 when Docker is available, `io.zonky.test:embedded-postgres` as an automatic, Docker-free fallback otherwise. Both give a real Postgres engine (not H2), which matters because the eventual schema relies on Postgres-specific features (materialized views, `CONCURRENTLY`, advisory locks, JSONB, partial unique indexes).

# GTD Analytical Platform — Phased Build Prompts for Claude Code

This is the execution plan for turning the approved HTML demo (`reference/gtd-platform-demo.html`) and the frozen architecture (`CLAUDE.md`, project root) into a real, running Spring Boot application. It assumes you're running **Claude Code** in the `GTD-Platform` folder, on a machine with real internet access (Maven Central, npm/CDN mirrors for vendoring frontend assets), JDK 21, and PostgreSQL 16 installed locally.

## How to use this document

1. Make sure `CLAUDE.md` is at the repo root — Claude Code reads it automatically for context on every phase.
2. Work through the phases **in order**. Each one has a "Prompt to paste" block — paste that verbatim as your message to Claude Code.
3. After each phase, run the acceptance criteria yourself (`mvn clean verify`, manual smoke test, etc.) before starting the next phase. If a phase fails its acceptance criteria, paste the failure back to Claude Code and ask it to fix it before moving on — don't stack unverified phases.
4. Commit after each phase passes. Small, reviewable commits per phase, not one giant commit at the end.
5. Phases 0–7 are the **MVP core**: Import, Auth, Executive Workspace, Country Workspace, Report Workspace, tested and hardened. Phases 8–12 are explicitly **follow-on work**, scoped for after you've reviewed the MVP.

## Decisions already made (don't re-ask Claude Code to reconsider these)

| Decision | Choice | Why |
|---|---|---|
| Target platforms | Windows + Linux + macOS | Confirmed — broader reach, JVM makes it cheap |
| Build scope | MVP core first | Import + Auth + Executive + Country + Report fully working before the other 8 workspaces / 3 admin screens |
| Packaging depth | Fully bundled offline installer | jlink + jpackage per OS, PostgreSQL bundled, fonts bundled — its own phase (11), not blocking the MVP |
| Import test data | Real GTD CSV available | `globalterrorismdb_0718dist.csv`, ~155MB, ~181,691 rows — the actual acceptance test for Phase 3 |
| Geospatial map tiles | Not sourced yet | Phase 9 (Geospatial) must flag this explicitly rather than fake tile data — everything else in that phase can still be built |

## Recommended defaults not yet frozen in the original spec (confirm or override before Phase 0)

- **Schema migrations:** Flyway (industry-standard for Spring Boot + Postgres, versioned and auditable — the alternative, Liquibase, is equally valid but Flyway's plain-SQL migrations fit a schema this DDL-heavy better).
- **Integration test strategy:** Testcontainers against real PostgreSQL 16 if Docker is available on your dev machine; `io.zonky.test:embedded-postgres` as a Docker-free fallback. Both give you a real Postgres, not H2 — important since this schema leans on Postgres-specific features (materialized views, `CONCURRENTLY`, advisory locks, JSONB, partial unique indexes).
- **PDF generation:** decide between Flying Saucer (renders your existing Thymeleaf HTML/CSS to PDF — reuses the approved demo's visual language almost directly) or OpenPDF (more programmatic, less reuse of existing templates). Phase 6's prompt asks Claude Code to pick one and document the choice in `CLAUDE.md`.

---

## Phase 0 — Project Foundation & Environment Setup

**Goal:** A Maven-buildable, runnable (empty) Spring Boot 3.x app with the correct package skeleton, config, and local Postgres connection — nothing functional yet, but everything after this builds on solid ground.

**Prompt to paste:**

> Read `CLAUDE.md` at the project root fully before starting. Set up the initial Spring Boot 3.x project for the GTD Analytical Platform, targeting Java 21, built with Maven.
>
> Scope for this phase only:
> 1. Generate `pom.xml` with: `spring-boot-starter-web`, `spring-boot-starter-thymeleaf`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-session-jdbc`, PostgreSQL JDBC driver, Flyway core + Flyway PostgreSQL, and test dependencies (JUnit 5, Mockito, AssertJ, Spring Boot Test, Testcontainers postgresql module, and `io.zonky.test:embedded-postgres` as a fallback). Pin all versions explicitly — no version ranges.
> 2. Create the package skeleton described in `CLAUDE.md` section 4 under `com.gtd.platform` — empty packages are fine for workspaces/services not yet built, but create the directories so the structure is visible.
> 3. Add `application.yml` with a `local` profile (connects to a local PostgreSQL instance on `localhost:5432`, database name `gtd_platform`, credentials externalized via environment variables, never hardcoded) and a `test` profile for the integration test strategy you choose.
> 4. Set up Logback config (console + rolling file appender, no external log shipping — this is air-gapped).
> 5. Add a global `.gitignore` (target/, IDE files, .env, logs).
> 6. Write a `README.md` at the project root covering: prerequisites (JDK 21, Maven, PostgreSQL 16, optionally Docker), how to create the local database, how to run `mvn clean verify`, how to run the app locally.
> 7. Add a single smoke-test integration test that boots the Spring context and confirms the app starts against your chosen test database strategy.
>
> Do not build any workspace, service, or Flyway migration yet — that's later phases. Ask me before adding any dependency not listed above. When done, tell me exactly what commands to run to verify this phase (`mvn clean verify` plus anything else), and update `CLAUDE.md` section 12/14 if you made a decision (e.g. Testcontainers vs embedded-postgres) that should be remembered going forward.

**Acceptance criteria:** `mvn clean verify` passes; `mvn spring-boot:run` starts the app without a running database causing a crash-loop (or the README clearly documents the one-time local DB setup step first); package structure matches `CLAUDE.md` §4.

---

## Phase 1 — Database Schema Foundation

**Goal:** The full frozen schema exists as Flyway migrations and runs cleanly against a fresh PostgreSQL 16 database, plus JPA entities/repositories for everything that isn't purely staging.

**Prompt to paste:**

> Read `CLAUDE.md` section 7 (Database schema) and section 8 (Cross-cutting rules) fully. Implement the complete database schema as versioned Flyway migrations under `src/main/resources/db/migration`.
>
> Required objects, in dependency order across as many `V__` migrations as makes sense:
> 1. `staging_incident` (all-TEXT columns) and `import_batch`.
> 2. Dimension tables `dim_region`, `dim_country`, `dim_organization`, `dim_attack_type`, `dim_weapon_type`, `dim_target_type` — each with a seeded `is_unknown = TRUE` row per AWS-UNK-001, not a nullable FK.
> 3. `dataset_version` with a partial unique index enforcing exactly one `is_active = TRUE` row.
> 4. `fact_incident` — PK `eventid VARCHAR(12)`, FK to `dataset_version_id`, denormalized `region_id` with a trigger enforcing consistency against `dim_country`, `nkill`/`nwound` nullable with `CHECK (... >= 0)` and never defaulted/coalesced anywhere in DDL.
> 5. Materialized views `mv_country_year_agg`, `mv_region_year_agg`, `mv_organization_year_agg`, `mv_attacktype_year_agg`, `mv_weapontype_year_agg`, `mv_targettype_year_agg`, refreshable `CONCURRENTLY`, plus a `materialized_view_status` table tracking last-refreshed timestamps.
> 6. `admin_user` and the Spring Session JDBC schema (reconcile with any separate `admin_session` concept — decide whether Spring Session's own tables are sufficient or whether `admin_session` is a distinct audit-oriented table, and document that decision).
> 7. `audit_log` — append-only (add a trigger or application-level convention preventing UPDATE/DELETE; document which approach you chose).
> 8. `generated_report` — immutable once created, `filter_snapshot JSONB`, `dataset_version_id`, no FK to `fact_incident`.
> 9. Draft an Error Catalog (`ERR-XXX-000` numbered codes, per `CLAUDE.md` §13.3) as a Java enum or properties file — just the structure and the first handful of entries relevant to import/validation errors; it'll grow as later phases add error cases.
>
> Then create corresponding JPA entities and Spring Data repositories for every table except `staging_incident` (which is loaded/queried more directly during import — decide the right abstraction and document it). Write integration tests (using the test DB strategy from Phase 0) that: run all migrations cleanly against an empty database, verify the "exactly one active dataset_version" constraint actually rejects a second active row, and verify the materialized views can be refreshed without error against seed data.
>
> Seed a small amount of reference dimension data (regions, a handful of countries/attack types/weapon types/target types plus their `is_unknown` rows) so later phases have something to join against before the real import pipeline exists.
>
> Ask me before deviating from the schema shape in `CLAUDE.md` §7. Update `CLAUDE.md` with any schema decisions you had to make that weren't already specified.

**Acceptance criteria:** Fresh `mvn clean verify` runs all migrations against a clean database with zero errors; the active-dataset-version constraint test and materialized-view-refresh test both pass; entities/repositories compile and have basic CRUD tests.

---

## Phase 2 — Auth & Shared Infrastructure

**Goal:** A working login/logout flow, global exception handling with the Error Catalog wired in, the standard API response envelope, canonical filter DTO, and an audit-logging mechanism that every later phase can reuse without re-inventing it.

**Prompt to paste:**

> Read `CLAUDE.md` section 9 (API conventions) and section 8 (cross-cutting rules) fully. Build the shared infrastructure layer that every workspace and service will depend on.
>
> Scope:
> 1. Session-based authentication using Spring Session JDBC. Single `admin_user` role only (no RBAC). Build login/logout endpoints and a minimal Thymeleaf login page (styled consistently with `reference/gtd-platform-demo.html`'s design system — dark/light theme tokens, IBM Plex fonts, the same brass/blue/red/green palette). Sessions must survive an app restart (that's the point of JDBC-backed sessions) — write a test proving this.
> 2. A `@ControllerAdvice` global exception handler that maps exceptions to the Error Catalog codes from Phase 1 and returns the standard envelope (`success`/`data`/`meta`/`error`) described in `CLAUDE.md` §9. Every endpoint in every future phase must use this envelope — don't let individual controllers invent their own error shapes.
> 3. The canonical filter request DTO (CAC-UI-003) — date range, region, country, attack type, weapon type, target type, organization — as a single reusable class every workspace filter bar can extend or compose, not duplicate.
> 4. An audit-logging mechanism (interceptor, AOP aspect, or explicit service call — your call, document the choice) that writes to `audit_log` on every state-changing action (login, logout, config change, import trigger, report generation). Write a test that performs a login and confirms an `audit_log` row appears.
> 5. Shared response DTOs for CAC-AN-001 (KPI), CAC-AN-002 (Ranking), CAC-AN-003 (Chart), CAC-AN-005 (Insights) — just the DTO shapes and serialization, no business logic yet. These get populated by SVC-AGG/SVC-TREND/INSIGHT in later phases.
>
> Write unit tests for the exception handler's mapping logic and integration tests for login/logout and audit-log writing. Ask before inventing any endpoint shape not implied by `CLAUDE.md` §9. Update the Error Catalog file from Phase 1 with any new codes you needed.

**Acceptance criteria:** Login/logout works end-to-end in an integration test; a deliberately-triggered exception returns the standard envelope with a correct Error Catalog code; an audited action produces an `audit_log` row; `mvn clean verify` passes.

---

## Phase 3 — Dataset Import Pipeline (IMPORT service)

**Goal:** Upload the real GTD CSV, have it staged, validated, cleansed, normalized into the dimension/fact tables, with duplicate detection and replace/append modes, running as a background job that survives session expiry, finishing within the 5-minute NFR target.

**Prompt to paste:**

> Read `CLAUDE.md` sections 7, 8, and 10 fully, especially AWS-IMPORT-001 and the ≤5 min / 155MB / ~180k row performance target. Build the IMPORT service end to end.
>
> Scope:
> 1. A file upload endpoint (multipart, size-limited appropriately, validated to be `.csv`) that stages the raw file into `staging_incident` as all-TEXT rows, recording an `import_batch` row with status tracking.
> 2. Validation and cleansing rules: reject/flag malformed rows (bad dates, non-numeric `nkill`/`nwound`, missing required fields) without ever coalescing null casualty counts to zero. Normalize staged rows into the dimension tables (creating new dimension rows as needed, resolving `is_unknown` appropriately per AWS-UNK-001) and then into `fact_incident`.
> 3. Duplicate detection on `eventid`, with both **replace** mode (supersede the row under a new `dataset_version`) and **append** mode (add without touching existing rows), matching whatever the original spec intended for "replace/append modes" in `CLAUDE.md` §11 functional scope — ask me if the exact replace/append semantics aren't fully clear from the spec.
> 4. Run the whole staging→validation→normalization pipeline as an async, session-independent background job (per AWS-IMPORT-001) protected by a single PostgreSQL advisory lock so only one import runs at a time. Only the final "activate this dataset_version" confirmation step should require an active session.
> 5. On successful import completion: deactivate the previous `dataset_version`, activate the new one (respecting the partial unique index from Phase 1), and refresh all materialized views `CONCURRENTLY`, updating `materialized_view_status`.
> 6. Expose a progress/status endpoint the UI can poll (rows staged so far, rows rejected, current phase).
> 7. Log every rejected row's reason somewhere queryable (this feeds the Data Quality Center in a later phase — don't lose the rejection detail even though DQ isn't built yet).
>
> The user has the real file: `globalterrorismdb_0718dist.csv` (~155MB, ~181,691 rows). Write an integration test that imports it end-to-end and asserts: it completes within 5 minutes on your dev machine, the row/rejection counts are sane, exactly one `dataset_version` ends up active, and the materialized views reflect the new data. Also write a concurrency test proving a second import attempt while one is running is rejected/queued by the advisory lock, not silently corrupting data.
>
> Ask before assuming exact validation thresholds not specified in the original spec. Flag clearly in your summary if the 5-minute target isn't met on the test machine, along with what's slow.

**Acceptance criteria:** Real GTD CSV imports successfully within 5 minutes; duplicate/replace/append behavior is covered by tests; concurrent import attempts are safely serialized; materialized views are correct after import; `mvn clean verify` passes.

---

## Phase 4 — Executive Workspace (end-to-end)

**Goal:** The flagship dashboard, functionally matching `reference/gtd-platform-demo.html`'s Executive Workspace, backed by real data from Phase 3's import.

**Prompt to paste:**

> Open `reference/gtd-platform-demo.html` and study its Executive Workspace section (KPI cards, top-countries ranking panel, incidents-by-year trend chart, attack/weapon/target breakdown charts, the Insights panel, the filter bar, and the "Generate report" action) — this is the UX contract. Read `CLAUDE.md` sections 6, 9, and 10.
>
> Build the Executive Workspace end-to-end against real imported data:
> 1. SVC-AGG: computes the global KPIs (SAV-001 through SAV-006 — total incidents, fatalities, injuries, success rate, countries affected, active organizations) from the materialized views, respecting AWS-UNK-001 exclusions where applicable, and respecting the canonical filter DTO from Phase 2 (date range, region, attack type, etc.).
> 2. SVC-TREND: computes the incidents-by-year trend series and the Growth Rate deviation figures used by the Insights panel (not the advanced stats reserved for SVC-STAT).
> 3. The top-countries ranking widget (CAC-AN-002) and the three category-breakdown charts (CAC-AN-003), all responding to the active filter set.
> 4. INSIGHT service: generate the narrative insight items (growth-rate deviation callouts, ranking deltas) shown in the Insights panel — real computation, not the demo's hardcoded text.
> 5. A Thymeleaf implementation of the workspace matching the demo's visual design (reuse its CSS design tokens as a real, locally-vendored stylesheet — no CDN fonts/Chart.js; vendor Apache ECharts locally instead, replacing the demo's Chart.js usage, since ECharts is the frozen frontend charting choice).
> 6. Wire the filter bar to actually re-query the backend (respecting the ≤2s filter-apply NFR) rather than the demo's client-side mock recompute.
> 7. The "Generate executive report" button should call into a stub REPORT endpoint for now (return "not yet implemented" cleanly) — full Report Workspace is Phase 6.
>
> Write unit tests for SVC-AGG/SVC-TREND/INSIGHT's calculations against known seed data (verify the SAV formulas are correct, not just that a number comes back), and an integration test that loads the Executive Workspace page and confirms it renders real data after Phase 3's import has run. Measure and report actual dashboard load time and filter-apply time against the ≤3s / ≤2s NFR targets.
>
> Ask me if any SAV-00x formula in `CLAUDE.md` §6 is ambiguous rather than guessing at the exact computation.

**Acceptance criteria:** Executive Workspace renders real data end-to-end; SAV formula unit tests pass; measured load/filter times are reported against NFR targets (and flagged if missed); visual layout matches the approved demo's structure and hierarchy; `mvn clean verify` passes.

---

## Phase 5 — Country Workspace

**Goal:** Country-level rankings, side-by-side comparison of up to four countries, and time-trend drill-down per country — the second workspace, reusing everything built in Phases 2–4 rather than duplicating it.

**Prompt to paste:**

> Read `CLAUDE.md` sections 6 and 9. Build the Country Workspace.
>
> Scope:
> 1. Country-level rankings (reuse the CAC-AN-002 ranking component from Phase 4 — don't rebuild it).
> 2. Side-by-side comparison of up to 4 countries at once (KPIs and trend charts per country, laid out for direct comparison) — this is SVC-COMPARE; implement it as its own service since it's referenced as a distinct analytical service in `CLAUDE.md` §4, not folded into SVC-AGG.
> 3. Time-trend drill-down for a single selected country (reuse SVC-TREND from Phase 4, parameterized by country).
> 4. Respect the canonical filter DTO and the standard response envelope throughout.
>
> Write unit tests for SVC-COMPARE and integration tests for the workspace's endpoints and page rendering. Confirm navigation state is preserved correctly when drilling down from the ranking into a single country's trend (per the Navigation State Model referenced in the original spec) — ask me if that transition behavior isn't clear from context.

**Acceptance criteria:** Country rankings, 4-way comparison, and per-country drill-down all work against real imported data; SVC-COMPARE has passing unit tests; `mvn clean verify` passes.

---

## Phase 6 — Report Workspace & REPORT service

**Goal:** Real PDF/CSV report generation, replacing the Phase 4 stub, with report history, immutable persistence, and the GTD attribution/citation requirement finally resolved.

**Prompt to paste:**

> Read `CLAUDE.md` sections 7, 8, and 13.1 (the GTD attribution open item). Build the REPORT service and Report Workspace.
>
> Scope:
> 1. Decide between Flying Saucer (Thymeleaf HTML/CSS → PDF, reuses the Executive Workspace's existing templates almost directly) and OpenPDF (more programmatic control, less template reuse) for PDF generation. Pick one, implement it, and record the decision plus rationale in `CLAUDE.md` §2.
> 2. REPORT service: given the current filter snapshot (from whichever workspace triggered it) and the active `dataset_version_id`, generate a PDF (and a CSV export option) covering KPIs, charts, and the Insights narrative — matching what Phase 4's Executive Workspace shows. Persist a `generated_report` row (immutable, `filter_snapshot` JSONB, no FK to `fact_incident`) isolated via `REPEATABLE READ`, independent of the IMPORT advisory lock.
> 3. Report Workspace UI: history table of past reports (name, format, generated timestamp, status, download), plus a "generate new report" flow, plus an empty state when no reports exist yet.
> 4. Resolve the GTD attribution/citation requirement (open item 13.1): add the required citation — "National Consortium for the Study of Terrorism and Responses to Terrorism (START), University of Maryland. (2018). The Global Terrorism Database (GTD) [Data file]. Retrieved from https://www.start.umd.edu/gtd" — plus copyright notice, as a persistent footer component shared across every workspace (not just Report), since it's a licensing obligation, not a Report-specific concern.
> 5. Wire Phase 4's "Generate executive report" button to this real implementation.
>
> Write unit tests for the REPORT service's PDF/CSV generation logic (at minimum: the right data ends up in the output, immutability is enforced — no update/delete path exists on `generated_report`) and an integration test for the full generate → persist → list → download flow. Measure actual report generation time against the ≤10s NFR target.

**Acceptance criteria:** Reports generate correctly in both formats, persist immutably, show up in history, and download; the citation footer appears platform-wide; measured generation time is reported against the ≤10s target; `mvn clean verify` passes.

---

## Phase 7 — MVP Testing & Hardening Pass

**Goal:** Before calling the MVP core "done," close every testing and security gap that individual phases didn't fully cover, and produce a real NFR verification report instead of scattered per-phase timing notes.

**Prompt to paste:**

> Read `CLAUDE.md` in full. Treat this phase as a hardening pass over everything built in Phases 0–6 — do not add new features.
>
> Scope:
> 1. Audit test coverage across Import, Auth, Executive, Country, and Report. Fill in any missing unit tests for SAV formulas (SAV-001 through SAV-008 as used so far) and any missing integration tests for endpoints that only got a happy-path test earlier.
> 2. Security review: confirm all POST endpoints are protected by the session auth from Phase 2 (except login itself); confirm CSRF protection is correctly configured for state-changing requests; confirm file upload validation (Phase 3) rejects oversized/malformed files safely; confirm no SQL is built via string concatenation anywhere (JPA/parameterized queries only); confirm session cookies are configured `HttpOnly`/`Secure`-appropriate for a local deployment.
> 3. Run the real GTD CSV import again and produce a concrete performance report: dashboard load time, filter-apply time, report generation time, import duration — each measured, each compared against its NFR target from `CLAUDE.md` §10, with a clear pass/fail per metric.
> 4. Concurrency/atomicity test pass on the import pipeline specifically: simulate a crash mid-import (kill the process or throw mid-transaction) and confirm no partial/corrupt `dataset_version` is left active.
> 5. Produce a short written summary (add it to `docs/`) of what's covered, what's known-weak, and what you'd recommend hardening further before this touches real production data.
>
> Do not silently lower an NFR target to make it "pass" — report the real number and flag it if it misses.

**Acceptance criteria:** No known untested SAV formula or endpoint in the MVP core; security checklist items all pass or are explicitly flagged with a remediation plan; a real NFR verification report exists; `mvn clean verify` passes with the full suite.

---

## MVP core ends here. Everything below is explicitly follow-on work — do not start until Phase 7 has been reviewed.

---

## Phase 8 — Remaining Core Analytical Workspaces

**Goal:** Region, Organization, Attack, Weapon, Target, Casualty — each following the exact same Shared Component Library pattern established in Phases 4–5, so this should move noticeably faster than the MVP phases.

**Prompt to paste:**

> Read `CLAUDE.md` and review how Country Workspace (Phase 5) reused Executive Workspace's (Phase 4) shared components rather than duplicating logic. Build the remaining six core analytical workspaces the same way, one at a time, each as its own commit: Region (regional rollups + member-country breakdown), Organization (org activity, geographic reach, dedicated unknown-organization handling per AWS-UNK-001), Attack (attack-type prevalence + success/failure trend), Weapon (weapon usage + casualty severity linkage), Target (target-category analysis), Casualty (admin-configurable severity bands, total casualties, **and** a statistical summary — this is the one categorical workspace that includes stats, per the original spec).
>
> For each workspace: reuse SVC-AGG/SVC-TREND/ranking/chart/insights components wherever the demo/spec calls for the same shape of output; only write new service logic where the workspace's analysis is genuinely different (e.g. Casualty's severity-band classification). Write unit + integration tests per workspace to the same standard as Phases 4–5. Do not move to the next workspace in the list until the current one's tests pass.

**Acceptance criteria:** All six workspaces functional against real data, each with passing unit/integration tests, each committed separately, each reusing shared components rather than duplicating them.

---

## Phase 9 — Advanced Analytics: Statistical & Geospatial Workspaces

**Goal:** SVC-STAT's exclusive advanced statistics, and the Geospatial Workspace's map exploration — with the map-tile gap handled honestly rather than faked.

**Prompt to paste:**

> Read `CLAUDE.md` §6 again — Trend Slope, Moving Average, Percentile Ranking, Correlation, and Outlier Detection belong exclusively to SVC-STAT/Statistical Workspace.
>
> Part A — Statistical Workspace: implement SVC-STAT with real, correctly-derived formulas for each of the five statistics above, expose them through the Statistical Workspace UI (CAC-AN-006 statistical summary component), and write unit tests that verify each formula against hand-computed or well-known reference values, not just "a number comes back."
>
> Part B — Geospatial Workspace: implement SVC-GEO (marker clustering, heatmap, choropleth, drawn-region spatial filtering, timeline animation) using Leaflet, vendored locally. **Offline map tiles have not been sourced yet.** Do not fake or hardcode fake tile URLs pointing at a public tile server (that would violate the zero-outbound-requests requirement and silently break in the real deployment). Instead: build the full Leaflet integration against a clearly documented placeholder tile layer (e.g. a local blank/grid tile set you generate), leave a clearly marked `TODO`-free but explicitly logged/documented gap describing exactly what an MBTiles package (or equivalent offline tile source) needs to provide and where it should be dropped in, and make swapping in real tiles later a config change, not a code change.
>
> Write integration tests for everything in Part B except actual tile rendering correctness (there's nothing to render yet).

**Acceptance criteria:** Statistical Workspace's five formulas are correct and tested; Geospatial Workspace fully functional except for real tile imagery, with the tile-source gap clearly documented as a deployment prerequisite, not silently stubbed.

---

## Phase 10 — Remaining Administrative Workspaces & Services

**Goal:** Administration, Data Quality Center, Audit & Activity Log, System Health Dashboard, plus the SEARCH and (remaining pieces of) CONFIG services.

**Prompt to paste:**

> Read `CLAUDE.md` fully, especially the CONFIG-service gap noted in the original spec (individual Administration FRs configure CONFIG values piecemeal; there's never been one consolidated CONFIG service).
>
> Build: Administration Workspace (login management already exists from Phase 2 — add import initiation/monitoring UI wired to Phase 3's IMPORT service, and a consolidated CONFIG service covering every setting referenced piecemeal across earlier phases — session timeout, duplicate-handling default, materialized-view auto-refresh toggle, notification preferences, etc. — as one real service, not scattered flags); Data Quality Center (import batch review UI surfacing the rejection detail Phase 3 already logs, with rejection/duplicate breakdowns); Audit & Activity Log (a queryable, filterable UI over the `audit_log` table from Phase 2, append-only, no edit/delete affordance anywhere in the UI); System Health Dashboard (DB connection status, active dataset version, materialized-view freshness from `materialized_view_status`, offline-verification indicator — actually verify zero outbound requests rather than hardcoding "passed"); SEARCH service (global search across workspaces, organizations, countries, and quick actions — the backend for the command-palette-style search pattern shown in `reference/gtd-platform-demo.html`).
>
> Write unit/integration tests per component to the established standard.

**Acceptance criteria:** All four remaining administrative workspaces functional against real data and the real audit log; CONFIG service consolidates settings rather than leaving them scattered; SEARCH works across all workspaces; `mvn clean verify` passes.

---

## Phase 11 — Offline Packaging & Installer

**Goal:** A single, OS-specific installer per platform that needs nothing pre-installed except the OS itself — matching the "fully bundled" choice made for this project.

**Prompt to paste:**

> Read `CLAUDE.md` §3 (target platforms) and §1 (zero-outbound-requests requirement). Build the offline packaging pipeline.
>
> Scope:
> 1. Use `jlink` to produce a minimal custom Java runtime image containing only the modules this app actually needs.
> 2. Use `jpackage` to produce a native installer per platform: `.msi` or `.exe` for Windows, `.deb` and/or `.rpm` (or an AppImage) for Linux, `.pkg` or `.dmg` for macOS — each bundling the jlink runtime, so end users never install a JDK separately.
> 3. Bundle PostgreSQL 16 for each platform (or, if that's not practical for a given OS's installer format, produce a clearly documented first-run setup step that initializes a bundled/embedded Postgres data directory automatically — pick whichever is more reliable per OS and document the difference).
> 4. Bundle all fonts and frontend assets locally (this should already be true from earlier phases — verify it, don't re-do it).
> 5. Build a genuine zero-outbound-requests verification: run the packaged app with network access blocked at the OS/firewall level and confirm it still functions fully (excluding the not-yet-sourced map tiles from Phase 9, which should already be documented as a known gap, not a silent failure).
> 6. Write a deployment runbook (`docs/DEPLOYMENT.md`) covering installation, first-run setup, upgrading (new dataset version import), and uninstallation, for all three platforms.
>
> Ask me before choosing between "bundle Postgres binaries directly" vs "documented first-run Postgres setup" if it isn't clearly better one way for a given OS — this is a real trade-off worth a quick check-in rather than a silent pick.

**Acceptance criteria:** Installers build successfully for all three platforms; a machine with zero prior Java/Postgres installation can install and run the app from the installer alone; the app functions with network access blocked (map tiles excepted and documented); `DEPLOYMENT.md` exists and is accurate.

---

## Phase 12 — Final Documentation & Traceability Closeout

**Goal:** Close out every documentation gap flagged in the original spec so the SRS is actually complete, not just "functionally" complete.

**Prompt to paste:**

> Read `CLAUDE.md` §13 (open items) in full, plus review the entire codebase's Javadoc/commit history for requirement-ID references built up over Phases 0–11.
>
> Produce: a Requirements Traceability Matrix (every FR/component/service mapped to its "Traces To" business goal, mechanically aggregated from code comments/Javadoc where you tagged requirement IDs — if earlier phases didn't tag consistently, note the gaps rather than fabricating traceability); the numbered Error Catalog, finalized and consolidated from what accumulated across Phases 1–10; Section 5 (External Interface Requirements — this app's only external interface is the local filesystem for CSV import and the bundled Postgres, document both precisely); Section 7 (consolidated Business Rules, aggregated from each workspace's individual rules rather than restated in prose from scratch); a final written decision on dimension-row-pruning after REPLACE-mode imports (either implement it now or formally defer it in writing with a clear rationale — no longer "flagged but undrafted"); a final NFR verification report covering the full, final system (not just the MVP core from Phase 7) against every target in `CLAUDE.md` §10.
>
> This phase produces documentation only — no new application features.

**Acceptance criteria:** Every open item listed in `CLAUDE.md` §13 is either resolved or has an explicit, documented decision; the traceability matrix, error catalog, and NFR report all exist under `docs/` and are accurate against the actual codebase, not aspirational.

# GTD Analytical Platform — Project Context

Read this file in full before touching any code. It captures every architectural decision that has already been made and is frozen — do not re-litigate these unless the user explicitly asks. When something isn't covered here, stop and ask rather than guessing.

## 1. What this is

A fully offline (air-gapped-compatible) Business Intelligence and Decision Support Platform built on top of the Global Terrorism Database (GTD). It gives government organizations, security agencies, researchers, and academic institutions interactive dashboards, geospatial visualization, statistical analysis, dynamic filtering, drill-down exploration, and executive PDF/CSV reporting — comparable in spirit to Power BI/Tableau/ArcGIS Dashboards, but running entirely without internet connectivity once installed.

An approved UI/UX reference exists at `reference/gtd-platform-demo.html` (static HTML/CSS/JS mockup, no backend) — treat its layout, component set, and visual language as the source of truth when building Thymeleaf views. It is illustrative only; none of its data or client-side logic should be copied verbatim into production code.

## 2. Frozen technology stack

- **Language/runtime:** Java 21 LTS
- **Framework:** Spring Boot 3.x, Spring Data JPA (Hibernate), Spring Validation, Spring Session (JDBC-backed — chosen specifically for offline/air-gapped compatibility, no Redis)
- **Build tool:** Maven
- **Database:** PostgreSQL 16+
- **Schema migrations:** Flyway (versioned SQL migrations under `src/main/resources/db/migration`)
- **Frontend:** Thymeleaf server-rendered views, Bootstrap (vendored locally, no CDN), Apache ECharts (vendored locally), Leaflet (vendored locally, offline map tiles)
- **Auth:** Session-based, single administrator role in V1 (no multi-admin, no RBAC, no external auth providers)
- **Testing:** JUnit 5, Mockito, AssertJ, Spring Boot Test; Testcontainers for integration tests against a real PostgreSQL 16 container if Docker is available on the dev machine, otherwise `io.zonky.test:embedded-postgres` as a Docker-free fallback that still runs a real Postgres binary
- **Reporting:** PDF generation via Flying Saucer (Thymeleaf → XHTML → PDF) or OpenPDF — pick one in Phase 6 and document the choice here
- **Zero outbound network requests at runtime** — every asset, font, library, and map tile must be bundled locally; no CDNs in production code

## 3. Target platforms

Development and deployment must both work on **Windows, Linux, and macOS**. The JVM makes this mostly free; the packaging phase (jlink + jpackage) needs OS-specific installer output for each.

## 4. Layered architecture (frozen hierarchy)

```
Platform
├── Analytical Workspaces (15, user-facing, sidebar nav)
│   Executive, Country, Region, Organization, Attack, Weapon, Target,
│   Casualty, Statistical, Geospatial, Report, Administration,
│   Data Quality Center, Audit & Activity Log, System Health Dashboard
├── Cross-Cutting Platform Services (4)
│   IMPORT, SEARCH, INSIGHT, CONFIG
└── Shared Infrastructure
    Auth, Logging, Exception Handling, Reporting, DB Access, Security,
    Validation, File Management
```

Package structure should mirror this: e.g. `com.gtd.platform.workspace.executive`, `com.gtd.platform.workspace.country`, `com.gtd.platform.service.imports`, `com.gtd.platform.service.insight`, `com.gtd.platform.shared.security`, `com.gtd.platform.shared.exception`, etc. Never let a workspace package depend on another workspace package directly — shared behavior goes through Shared Infrastructure or a cross-cutting service.

## 5. Requirement ID taxonomy (frozen — use in code comments, commit messages, and Javadoc where relevant)

- **Workspace prefixes:** EXEC, COUNTRY, REGION, ORG, ATTACK, WEAPON, TARGET, CASUALTY, STAT, MAP, REPORT-WS, ADMIN, DQ, AUDIT, HEALTH
- **Service prefixes:** SVC-AGG, SVC-TREND, SVC-STAT, SVC-GEO, SVC-COMPARE, INSIGHT, REPORT, SEARCH, CONFIG
- **NFR prefixes:** PERF, SEC, REL, TIME, USE, MAINT
- **Other:** SAV (shared analytical vocabulary), ADC (analytical data contracts), COMP-UI/COMP-AN/COMP-INT (shared components), AWM/AWS-UNK/AWS-IMPORT (workflow/architecture rules)

## 6. Shared Analytical Vocabulary (SAV-001–017) — exactly one computation owner each

Incident, Fatalities, Injuries, Success Rate, Attack Density, Distinct Entity Count, Avg Fatalities/Injuries per Incident, Growth Rate, Moving Average, Trend Slope, Percentile Ranking, Total Casualties, Severity Classification, Correlation, Outlier.

**Trend Slope, Moving Average, Percentile Ranking, Correlation, and Outlier Detection belong exclusively to the Statistical Workspace / SVC-STAT.** No other workspace computes these — Insights panels elsewhere use SVC-TREND's simpler Growth Rate deviation instead.

## 7. Database schema (target shape — implement via Flyway in Phase 1)

- **Staging:** `staging_incident` (all-TEXT columns, raw), `import_batch`
- **Dimensions:** `dim_region`, `dim_country`, `dim_organization`, `dim_attack_type`, `dim_weapon_type`, `dim_target_type` — each has an `is_unknown` flag **row** (not a nullable FK) to support AWS-UNK-001
- **Fact table:** `fact_incident` (PK = `eventid` VARCHAR(12)), with `dataset_version_id`, denormalized `region_id` (consistency enforced via trigger against `dim_country`), nullable `nkill`/`nwound` (CHECK ≥ 0, **never coalesced to zero**)
- **Dataset versioning:** `dataset_version`, partial unique index enforcing exactly one `is_active = TRUE` row
- **Materialized views:** `mv_country_year_agg`, `mv_region_year_agg`, `mv_organization_year_agg`, `mv_attacktype_year_agg`, `mv_weapontype_year_agg`, `mv_targettype_year_agg` — refreshed `CONCURRENTLY` on import completion; freshness tracked via `materialized_view_status`
- **Auth:** `admin_user`, `admin_session` (or Spring Session's own JDBC schema — reconcile these in Phase 2)
- **Audit:** `audit_log` (append-only, no updates/deletes)
- **Reports:** `generated_report` (immutable once created; no FK to `fact_incident` — stores `filter_snapshot` JSONB + `dataset_version_id` instead)

## 8. Cross-cutting rules (frozen)

- **AWS-UNK-001:** Unknown/unspecified categorical values are excluded from named-entity aggregates at query time for Organization, Attack Type, Weapon Type, Target Type. Country/Region use staging-level exclusion instead (different mechanism).
- **AWS-IMPORT-001:** Import staging/validation runs as a session-independent background job; only the final confirmation step requires an active session. Large uploads (155MB+) must not be coupled to session timeout.
- Import concurrency is a single PostgreSQL advisory lock (one import at a time). Report generation is isolated via `REPEATABLE READ`, independent of the import lock.
- REPORT and SEARCH are the only two services allowed to break strict statelessness (REPORT persists artifacts; SEARCH reads cross-cutting metadata) — a deliberate, narrow, documented exception.
- Distinct-count/mode-based metrics are **Derived Metrics**, never Required Measures.
- `nkill`/`nwound` nulls are never coalesced to zero — enforced at both the CHECK constraint and the service-query level.

## 9. API conventions (frozen)

All endpoints are POST, with a standard response envelope: `success` / `data` / `meta` / `error`. Canonical request schema for filters is CAC-UI-003. Canonical response schemas per shared component: CAC-AN-001 (KPI), CAC-AN-002 (Ranking), CAC-AN-003 (Chart), CAC-AN-004 (Map), CAC-AN-005 (Insights), CAC-AN-006 (Statistical Summary).

## 10. Non-functional targets (must be validated, not assumed)

- Dashboard load ≤ 3s, filter apply ≤ 2s, search ≤ 1s, report generation ≤ 10s, import of ~180k rows / 155MB ≤ 5 min
- Reference hardware baseline: 8-core CPU/2.5GHz+, 16GB RAM, SSD, up to 250,000 records
- WCAG 2.1 Level A (AA target where feasible)
- Zero outbound internet requests post-install

## 11. Explicitly out of scope for V1

Live terrorism feeds, cloud deployment, mobile apps, predictive ML, public REST APIs, multi-user collaboration, advanced RBAC, external auth providers, distributed databases, multi-language localization.

## 12. Build scope decision for this engagement

This project is being built **MVP-core-first**: get Import + Auth + Executive Workspace + Country Workspace + Report Workspace fully working, tested, end-to-end before starting the remaining 8 workspaces and 3 remaining administrative screens. See `docs/BUILD-PHASES.md` for the exact phase sequence and one ready-to-paste prompt per phase. Do not jump ahead to a later phase's scope even if it seems quick — each phase has its own acceptance criteria and should be committed and verified before the next begins.

### Phase 0 decisions (implemented, keep in sync if revisited)

- **Spring Boot version:** pinned to `3.3.4` via `spring-boot-starter-parent`. Its `spring-boot-dependencies` BOM manages exact (non-range) versions for every starter, Spring Session, Flyway, the PostgreSQL driver, JUnit 5/Mockito/AssertJ, and Testcontainers (via the BOM's `testcontainers-bom` import). The only dependency version pinned by hand in `pom.xml` is `io.zonky.test:embedded-postgres` (`2.0.7`), since it isn't covered by that BOM.
- **Test database strategy (implemented):** `com.gtd.platform.testsupport.PostgresIntegrationTestBase` picks the strategy automatically at test-startup via `DockerClientFactory.instance().client()` — a Testcontainers `postgres:16-alpine` container when Docker responds, otherwise an `io.zonky.test` embedded-postgres instance. Both register real `spring.datasource.*` properties via `@DynamicPropertySource`. Every future integration test that needs a real Postgres should extend this base class rather than reinventing container/embedded-postgres setup.
- **Spring Session JDBC schema:** `spring.session.jdbc.initialize-schema` is set to `never` in `application.yml` — resolved permanently in Phase 1, see below (Spring Session's tables are now created by Flyway instead of its own initializer, so this stays `never` going forward, not just "for now"). `cleanup-cron` was disabled in Phase 0 as a stopgap and has been reverted in Phase 1 now that the table exists.

### Phase 1 decisions (implemented, keep in sync if revisited)

- **`admin_session` reconciliation (resolves open item 6 below):** Spring Session's own JDBC schema (`spring_session` / `spring_session_attributes`, copied verbatim from `spring-session-jdbc-3.3.2`'s `schema-postgresql.sql` into `V6__admin_user_and_spring_session.sql`) is the sole store for active sessions — no separate `admin_session` table was created. The "audit-oriented" half of what `admin_session` might have covered is handled entirely by `audit_log` (also section 7), which is the platform's one audit mechanism; a second, narrower audit table would just duplicate it. Phase 2's login/logout flow should write to `audit_log`, not invent anything new.
- **`staging_incident` column list:** no real GTD CSV file is present in this environment, so the ~130 TEXT columns in `V1__staging_and_import_batch.sql` are a best-effort reconstruction of the public GTD Codebook (the release associated with `globalterrorismdb_0718dist.csv`), not verified against the actual file header. **Phase 3 must reconcile this column list against the real CSV header row before the first production import** and add any missing columns via a new forward-only migration — never edit `V1` once it has shipped.
- **`staging_incident` has no JPA entity/repository:** it's ~180k rows of raw TEXT per import, loaded and read back essentially once; a 130+-column entity would be brittle against the Phase 3 CSV reconciliation above, and row-by-row entity persistence can't hit the ≤5 min/155MB import NFR (section 10). Phase 3's IMPORT service should access it via direct JDBC (`JdbcTemplate`/`COPY`), documented in `service.imports`'s package-info.
- **`fact_incident`'s FK set:** section 7 only explicitly calls out `dataset_version_id` and the denormalized `region_id`. `country_id`, `organization_id`, `attack_type_id`, `weapon_type_id`, and `target_type_id` (all `NOT NULL`, per the "not a nullable FK" rule) were added because the six `mv_*_year_agg` views — also required by section 7 — need something to group by. All six are `NOT NULL` because every dim_* table's seeded `is_unknown` row exists precisely so a fact row is never forced to leave one null.
- **Append-only/immutability enforcement (`audit_log`, `generated_report`):** a database trigger (`fn_reject_table_mutation()`, defined once in `V7__audit_log.sql`, reused by `V8__generated_report.sql`), not an application-level convention. A trigger can't be silently bypassed by a future repository method, a raw SQL session, or an ORM bug — only a new migration can lift it.
- **Materialized view aggregates never coalesce nulls to zero:** `fatality_count`/`injury_count` are plain `SUM(nkill)`/`SUM(nwound)` with no outer `COALESCE(..., 0)`. `SUM()` already skips NULL inputs per standard SQL (doesn't treat them as zero), so a group where every incident has unknown casualties correctly yields a NULL aggregate — consistent with the "nkill/nwound nulls are never coalesced to zero" rule (sections 7/8) extended to the aggregate level.
- **Materialized views created `WITH DATA` (default), not `WITH NO DATA`:** `REFRESH MATERIALIZED VIEW CONCURRENTLY` requires a view to have been populated at least once already. Creating `WITH DATA` against an empty `fact_incident` at migration time (zero rows, but "populated") satisfies that precondition immediately, so Phase 3's first real refresh — and this phase's own integration test — can use `CONCURRENTLY` right away instead of needing a bootstrap non-concurrent refresh first.
- **Entity package placement:** all core relational entities except `admin_user` (`shared.auth`), `audit_log` (`shared.logging`), `generated_report` (`shared.reporting`), and `staging_incident` (no entity, see above) live in `shared.db` — including `import_batch` and `dataset_version`, even though `import_batch` conceptually "belongs" to the IMPORT service. Putting it in `service.imports` instead would make `shared.db` (which needs `DatasetVersion` → `ImportBatch`) depend on a service package, inverting the intended dependency direction and risking a cycle once Phase 3's IMPORT service depends on `shared.db`'s `DatasetVersion`/`FactIncident`. Keeping every shared entity in `shared.db` means services depend on shared infra, never the reverse.
- **Reference dimension seed data scope:** regions, attack types, weapon types, and target types are small, fixed, real GTD Codebook enumerations, so the *full* canonical set is seeded for each (12/9/13/22 respectively, each plus its `is_unknown` row) — there's no meaningful "handful" short of the real list. Countries (26, spanning all 12 regions) and organizations (9) are open-ended domains in the real dataset, so only a representative handful is seeded; Phase 3's import normalizes the rest from the real CSV.

### Phase 2 decisions (implemented, keep in sync if revisited)

- **`spring-boot-starter-security` added** (plus `spring-security-test`): not in CLAUDE.md's originally frozen stack (section 2), and added only after asking the user directly, since real login/logout needs password hashing, CSRF protection, and session-fixation protection - all security-critical code better served by the standard library than hand-rolled. It integrates with `spring-session-jdbc` (already frozen) with no extra wiring: `SecurityContext` is persisted through the same JDBC-backed session store.
- **`shared.api` added as a ninth Shared Infrastructure package:** none of CLAUDE.md section 4's original eight (Auth, Logging, Exception Handling, Reporting, DB Access, Security, Validation, File Management) cleanly fit "the envelope every controller wraps its response in" or "the shared CAC-UI-003/CAC-AN-00x DTO shapes." Holds `ApiResponse`/`ApiError` (the section 9 envelope), `AnalyticalFilterRequest` (CAC-UI-003), and `KpiResponse`/`RankingResponse`/`ChartResponse`/`InsightsResponse` (CAC-AN-001/002/003/005 - CAC-AN-004 Map and CAC-AN-006 Statistical Summary are deferred to whichever phase first needs them, Geospatial/Statistical). `shared.exception` depends on `shared.api` for the envelope, never the reverse.
- **Audit-logging mechanism: explicit service call**, not AOP/an interceptor - `AuditService.record(...)` in `shared.logging`. CONFIG/IMPORT/REPORT (the other state-changing actions named in the prompt) don't exist yet, so there is nothing yet for an aspect to intercept; when those services are built, they call `AuditService.record(...)` directly, the same way `AuthenticationAuditListener`/`AuditingLogoutSuccessHandler` do here for login/logout. `audit_log.details` (jsonb) is exposed as `Map<String,Object>` and serialized internally via Jackson, so callers never hand-build JSON strings.
- **Login/logout audit wiring:** login success/failure use Spring Security's own `AuthenticationSuccessEvent`/`AbstractAuthenticationFailureEvent` (published automatically once `spring-boot-starter-security` is present - no extra publisher config needed) via `AuthenticationAuditListener`, kept separate from the actual redirect logic (`SecurityConfig`'s `defaultSuccessUrl`/`failureUrl`) since auditing is a side effect, not a flow-control decision. Logout has no equivalent auto-published event, so `AuditingLogoutSuccessHandler` (a `LogoutSuccessHandler` wired into `SecurityConfig`) does both the audit write and the redirect.
- **Admin bootstrap on first run:** not asked for explicitly, but without it a fresh database has no way to log in at all (every Phase 2 acceptance criterion would only be demonstrable in tests, which create their own fixtures). `AdminBootstrapRunner` (`shared.auth`, `@Profile("!test")`) creates one `admin_user` with a random per-install password if `admin_user` is empty, logging it once at `WARN` via SLF4J - the same pattern Jenkins uses for the same air-gapped, no-side-channel first-run problem. Flagged prominently for the user to reconsider (e.g. replace with a real first-run setup screen) rather than decided silently.
- **Login/logout endpoint shapes:** `GET /login` (renders the page) and the Spring-Security-handled `POST /login`/`POST /logout` do **not** use the section 9 `success`/`data`/`meta`/`error` envelope - that convention governs the analytical JSON endpoints later phases build (CAC-AN-*), not server-rendered form/redirect flows. `POST /login` and `POST /logout` are already POST by virtue of being standard HTML form submissions, so section 9's "all endpoints are POST" is satisfied without needing to invent anything.
- **Fonts vendored for real, not stubbed:** `reference/gtd-platform-demo.html` links IBM Plex from Google Fonts, which the zero-outbound-requests rule (section 1) forbids in production. The 7 static woff2 files actually used (Sans 400/500/600/700, Mono 400/500/600, latin subset) were downloaded once during this build session and committed under `src/main/resources/static/fonts/` - `gtd-theme.css` references them locally, no CDN link anywhere in `src/main`.

### Phase 3 decisions (implemented, keep in sync if revisited)

- **REPLACE/APPEND semantics: row-level upsert**, confirmed with the user given `fact_incident`'s single-column `eventid` PK (section 7) means the table holds one current row per event, never a historical snapshot per `dataset_version`. REPLACE: `INSERT ... ON CONFLICT (eventid) DO UPDATE` (overwrite an existing row, reassign its `dataset_version_id`). APPEND: `INSERT ... ON CONFLICT (eventid) DO NOTHING` (leave an existing row untouched, log the incoming duplicate as rejected with reason `DUPLICATE_EVENTID_APPEND_SKIPPED`). **Neither mode deletes a row absent from the new file** - both are implemented in `FactIncidentBulkWriter` via raw batched JDBC, never JPA entity saves (can't hit the NFR otherwise).
- **`commons-csv` added**, with the user's explicit sign-off: a hand-rolled parser risks silent column misalignment on the real file's quoted/escaped `summary`/`scite*` fields - a correctness hazard, not just convenience.
- **The real GTD CSV still isn't present in this environment** (confirmed with the user, who will place it locally). `RealFileImportPerformanceTest` resolves it from `GTD_REAL_CSV_PATH` or `<project root>/data/globalterrorismdb_0718dist.csv` (gitignored) and skips itself with a clear message if absent, rather than failing `mvn clean verify`. Run it manually once the file is in place to get the real ≤5 min NFR measurement - it has not been run against the real file as of this phase.
- **Country/Region use AWS-UNK-001's "staging-level exclusion"; Organization/Attack/Weapon/Target Type use the is_unknown-row fallback** - both mechanisms named in section 8 but not previously implemented. Concretely: a row with a blank/unresolvable country (or a new country whose `region_txt` doesn't match one of the 12 seeded canonical regions) is **rejected outright** (`UNRESOLVABLE_COUNTRY_OR_REGION`), never mapped to an is_unknown dimension row. A blank organization/attack/weapon/target value resolves to that dimension's seeded is_unknown row instead - the row is still accepted. New non-blank values for any of the six dimensions create a new row (regions are the one exception: treated as a closed, well-known set - a new/unmatched region name is never auto-created, only ever causes rejection via the country-resolution path above).
- **A country's region assignment is fixed at first resolution, never re-derived per row:** `DimensionResolver.resolveCountry` returns a cached country's stored region even if a later row's `region_txt` disagrees, specifically so `fact_incident`'s region-consistency trigger (V4 migration) can never be violated by a normalization bug - region is only read from a row's own data when *creating* a brand-new country.
- **Only the primary attack/weapon/target type and perpetrator are used** (`attacktype1`/`weaptype1`/`targtype1`/`gname`) - continues Phase 1's single-column `fact_incident` schema rather than silently introducing a multi-valued join table. An incident coded with a secondary attack/weapon/target type in the source data loses that secondary classification. Flagged here as a known limitation, not silently absorbed.
- **Casualty count parsing is lenient**: `nkill`/`nwound` accept both `"5"` and pandas-export-style `"5.0"` (a Kaggle CSV export routinely upcasts an int column with missing values to float), and `"NaN"`/blank both mean unknown (`null`, never coalesced to zero - sections 7/8). Only genuinely non-numeric text or a negative value rejects the row.
- **`import_rejected_row` added** (V10 migration) - not part of the original section 7 schema list, added because the Phase 3 prompt explicitly requires rejection detail to stay queryable for the future Data Quality Center. Lives in `service.imports` (not `shared.db`): nothing else needs to reference it, unlike `import_batch`/`dataset_version` in Phase 1.
- **The advisory lock uses a dedicated, never-pool-returned `Connection`** (`ImportAdvisoryLockService`), not a connection borrowed-and-returned through normal Spring transaction management: PostgreSQL advisory locks are session-scoped, and a connection cycled back through the pool mid-import risks the lock being silently dropped if HikariCP evicts it as idle.
- **`@Async`/`@Transactional` self-invocation avoided by splitting into separate beans**: `ImportOrchestrationService` (synchronous: acquire lock, create `import_batch`) hands off to `ImportJobRunner` (the actual `@Async` background job) as two different beans specifically so the `@Async` proxy isn't bypassed by a same-object method call; likewise `DatasetVersionActivationService`'s `@Transactional` activation is a separate bean from `ImportConfirmationService`. `ImportJobRunner`'s own internal per-row status-update helpers are *not* `@Transactional` (a self-invocation no-op there too) - documented inline as deliberate, since each is a single find-then-save with no other concurrent writer, not a gap.
- **`REFRESH MATERIALIZED VIEW CONCURRENTLY` is never called from inside a transaction** (PostgreSQL rejects it): `MaterializedViewRefreshService` is plain, non-transactional, and `ImportConfirmationService` only calls it after `DatasetVersionActivationService`'s own transaction has already committed.
- **"Session-independent" (AWS-IMPORT-001) means the background job's lifecycle is decoupled from any one HTTP session's lifetime, not that `/api/import/*` is unauthenticated.** Every IMPORT endpoint sits behind the same authentication as the rest of the platform; an unauthenticated upload endpoint would be a real security hole. The actual decoupling comes from staging/normalization running on `ImportJobRunner`'s dedicated executor thread, persisting all progress in `import_batch`, independent of the request/session that triggered it.
- **Confirmation is an explicit second step, not automatic**: the async job ends in `AWAITING_CONFIRMATION`, never activating the new `dataset_version` itself. This matches AWS-IMPORT-001's framing of confirmation as its own distinct, session-requiring step, and gives an admin a checkpoint to review rejection counts (surfaced by the status endpoint) before committing.
- **Dimension row pruning after REPLACE (open item 2 below) is narrower than originally framed, and still not implemented.** With row-level upsert semantics (not wholesale table replacement), a dimension row can only become orphaned if a REPLACE changes an existing fact row's classification (e.g. reclassifying an incident from one attack type to another) - there is no scenario here where an entire file re-import deletes swaths of fact data and strands many dimension rows at once, since nothing is ever deleted. Still deferred to Future Extensions; still worth a periodic cleanup job eventually.

## 13. Open items inherited from the original spec (resolve when their phase comes up)

1. GTD attribution/citation requirement (EULA obligation) — must appear in the UI footer via a shared component. Resolve in Phase 6 (Report Workspace / global layout).
2. Dimension row pruning after REPLACE-mode imports — revisited in Phase 3 (see "Phase 3 decisions" under section 12): narrower than originally framed given row-level upsert semantics, but still not implemented; still deferred to Future Extensions.
3. ~~Error Catalog~~ — **resolved in Phase 2**: `com.gtd.platform.shared.exception.ErrorCode` (drafted in Phase 1) is now wired into `GlobalExceptionHandler` (`@RestControllerAdvice`) and the standard envelope (`shared.api.ApiResponse`); `AUTH`/`REQUEST` categories were added for Phase 2's needs. New codes are still appended here as later phases need them (e.g. IMPORT/REPORT-specific ones).
4. Requirements Traceability Matrix, Section 5 (External Interface Requirements), Section 7 (consolidated Business Rules) — close these out in the final documentation phase.
5. Offline map tiles for the Geospatial Workspace are **not yet sourced** — flag this explicitly when that phase comes up rather than silently faking tile data.
6. ~~Spring Session JDBC schema initialization~~ — **resolved in Phase 1**: `admin_user` and Spring Session's own schema are both created via Flyway (`V6__admin_user_and_spring_session.sql`); `admin_session` was decided against in favor of reusing `audit_log`. See "Phase 1 decisions" under section 12.

## 14. Coding standards

- Write Javadoc on every public service method explaining which SAV/ADC/FR it implements.
- No wildcard imports. No `System.out.println` — use SLF4J.
- Every SQL migration is forward-only; never edit a Flyway migration that has already been committed — add a new one.
- Every new workspace/service gets: entity + repository (if needed) + service (with unit tests) + controller (with integration test) + Thymeleaf view.
- Commit messages reference the requirement ID(s) they implement, e.g. `feat(EXEC): add KPI aggregation service (SVC-AGG, SAV-001..004)`.

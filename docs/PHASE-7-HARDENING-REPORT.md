# Phase 7 — MVP Testing & Hardening Pass Report

**Date:** 2026-07-30
**Scope:** Hardening pass over Phases 0–6 (Import, Auth, Executive, Country, Report). No new features added — see `CLAUDE.md` §12 for the frozen build-phase sequence this closes out.

## Headline finding: a real, production-blocking bug caught and fixed

The real GTD CSV (`globalterrorismdb_0718dist.csv`, 155MB / 181,691 rows) was placed and imported for the first time this phase. It **failed immediately** with `MalformedInputException` — `CsvStagingLoader` read the file as UTF-8, but the real file is encoded in **Windows-1252** (confirmed via `iconv`: invalid as UTF-8, valid as Windows-1252/ISO-8859-1 — a well-known characteristic of this dataset's free-text fields, which contain accented names and Windows/Excel-style smart quotes and em-dashes).

**Fixed:** `CsvStagingLoader` now reads with `Charset.forName("windows-1252")` explicitly, documented inline. After the fix, the real file imported cleanly with **zero rejected rows**. This directly resolves the encoding half of the open concern flagged back in Phase 1/3 ("reconcile `staging_incident`'s column list against the real CSV before first production import") — the column list itself matched perfectly; the encoding did not, and would have blocked every real-world import until caught.

A second, smaller bug was found and fixed in the same pass: `RealFileImportPerformanceTest` asserted `dataset_version.row_count` against "rows accepted from this file" rather than the actual `fact_incident` table total (which is what that column is defined to hold — see Phase 1 decisions in `CLAUDE.md`). This only surfaced once the real file was actually available to run the test against a database already containing rows from other tests.

## 1. Test coverage audit

Reviewed every SAV formula in use (SAV-001–006, SAV-008 — SAV-007 "Avg Fatalities/Injuries per Incident" is not implemented by any workspace yet, correctly so per the MVP-core scope) and every controller endpoint across Import/Auth/Executive/Country/Report. Existing coverage was already strong (hand-computed-value tests for every SAV formula, `GrowthRateCalculatorTest`'s 6 edge cases, `InsightServiceTest`'s real-data narrative assertions). Gaps found and closed:

| Gap | Fix |
|---|---|
| No test for non-CSV/empty file upload rejection (`ERR-IMPORT-001`) | `ImportValidationIntegrationTest` |
| No test for oversized upload rejection (`ERR-IMPORT-002`) | `ImportOversizedFileTest` (dedicated small `max-file-size` context — exceeding the real 250MB limit in a test would be slow) |
| No test for unknown import-batch-id lookups (`ERR-IMPORT-004`) on `/status` or `/confirm` | `ImportValidationIntegrationTest` |
| No test for confirming an already-completed batch (`ERR-IMPORT-005`) | `ImportValidationIntegrationTest` |
| No test for downloading a `FAILED` report (`ERR-REPORT-005`) | Added to `ReportGenerationServiceFailureTest` |
| `DatasetVersionActivationService` (the exactly-one-active-version invariant) had **zero** direct test coverage | `ImportCrashAtomicityTest` (see §4) |

All new/existing tests: **80 total, 0 failures**, stable across repeated full-suite runs.

## 2. Security review

| Item | Result |
|---|---|
| All POST endpoints require session auth (except `/login`) | **Confirmed empirically**, not just by reading config — `SecurityHardeningTest` hits a representative endpoint per workspace/service (`/api/import/status`, `/api/executive/dashboard`, `/api/country/ranking`, `/api/report/history`) and every protected page (`/`, `/workspace/*`) with no session and confirms none return 200. |
| CSRF protection on state-changing requests | **Confirmed empirically** — an authenticated POST to `/api/report/history` with no `X-CSRF-TOKEN` header returns 403. |
| File upload validation rejects oversized/malformed files safely | **Confirmed** — non-CSV extension, empty file, and oversized file all rejected with the correct `ERR-IMPORT-00x` code before any staging work begins (see §1). |
| No SQL built via string concatenation | **Audited, clean.** Every dynamic-SQL construction site in `src/main` was traced (`RankingAndBreakdownService`, `TrendService`, `InsightService`, `CsvStagingLoader`, `FactIncidentBulkWriter`, `MaterializedViewRefreshService`). In every case, concatenated fragments are fixed, server-chosen identifiers (view/column/table names from internal constants, never from a request), and every actual *value* is bound via `?` JDBC parameters or JPA. `CsvStagingLoader` is the one place a user-uploaded file's column names influence a query at all, and it works by **intersecting** the CSV header against a fixed internal allowlist (`STAGING_COLUMNS`) — only names already in that allowlist are ever concatenated into SQL, so an attacker-controlled header value can never reach the query text. |
| Session cookie `HttpOnly`/`Secure`-appropriate | **Confirmed** — `Set-Cookie` carries `HttpOnly`. `Secure` is deliberately **absent**, which is correct for this platform's frozen offline/plain-HTTP local deployment model (§1 of `CLAUDE.md`): marking it `Secure` would mean the browser never sends it back over plain HTTP, breaking every request after login. This is not a gap. |

## 3. Performance report (real GTD CSV, real running app, `local` profile)

Measured against the actual 181,691-row file, on top of ~3,000 pre-existing rows already in the local dev database (184,693 total — slightly past the 250k reference scale is not reached, but this is a real, non-trivial dataset, not a toy).

| Metric | Target (§10) | Measured | Result |
|---|---|---|---|
| Import duration (upload → confirmed) | ≤ 5 min | **27.7s** | **PASS** (≈10x headroom) |
| Dashboard load (`GET /workspace/executive`) | ≤ 3s | **251ms** | **PASS** |
| Filter apply, unfiltered (`POST /api/executive/dashboard`) | ≤ 2s | **183ms** | **PASS** |
| Filter apply, date-range filtered (2015–2017) | ≤ 2s | **59ms** | **PASS** |
| Report generation (PDF, full dataset) | ≤ 10s | **471ms** | **PASS** |
| Country Workspace load (bonus, not in §10's named list) | — | 30ms | informational |
| Search | ≤ 1s | — | **N/A — SEARCH service not built yet** (out of MVP-core scope per §12) |

No target was silently lowered or skipped to make it pass — these are the real, measured numbers, all well within target with substantial headroom even on this reference machine.

## 4. Concurrency/atomicity: simulated crash mid-import

`ImportCrashAtomicityTest` forces a real, Spring-managed `RuntimeException` inside `DatasetVersionActivationService.activate()` — the one `@Transactional` method that owns the "exactly one active `dataset_version`" invariant (deactivate old → activate new → mark batch `COMPLETED`) — via a `@MockBean`-replaced `FactIncidentRepository.count()` throwing on its last call before commit. Confirmed:

- The transaction rolls back completely: the **original** dataset_version is still active (not zero, not two).
- The candidate version is still inactive, `activatedAt` still null.
- The `import_batch` is still `AWAITING_CONFIRMATION`, not incorrectly `COMPLETED` — so a retry is possible.
- A subsequent retry (crash condition cleared) succeeds cleanly and the invariant holds afterward.

This is a genuine, not merely theoretical, guarantee: it was previously undocumented by any test (`DatasetVersionActivationService` had zero prior coverage).

## What's known-weak / recommended before touching real production data

1. **No SEARCH service yet** — the ≤1s search NFR is entirely unvalidated because nothing implements it yet (Phase 10 scope).
2. **Dimension row pruning after REPLACE-mode imports is still not implemented** (open item 2, `CLAUDE.md` §13) — narrow in practice (row-level upsert semantics mean nothing is ever bulk-deleted), but worth a periodic cleanup job before long-term production use.
3. **Only the primary attack/weapon/target type and perpetrator are imported** (`attacktype1`/`weaptype1`/`targtype1`/`gname`) — an incident with a coded secondary classification silently loses it. Flagged since Phase 3, still true.
4. **The `staging_incident` column list was a best-effort reconstruction until this phase** — now verified against the real file's actual header for the first time, and matched perfectly (no columns needed the "dropped" path). The **encoding** did not match, and is now fixed (see above). Recommend re-running `RealFileImportPerformanceTest` against any *future* GTD release before assuming this continues to hold.
5. **Offline map tiles for the Geospatial Workspace remain unsourced** (open item 5) — irrelevant until that workspace is built, but still an open gap.
6. **AdminBootstrapRunner's random-password-in-the-log first-run flow** (Phase 2 decision) is a reasonable stopgap for an air-gapped install but was explicitly flagged for reconsideration before this becomes a real multi-install production tool — worth a real first-run setup screen eventually.
7. **Single-admin, no RBAC** is a frozen V1 decision (§2, §11), not a gap — but worth restating here since it's the kind of thing a security reviewer unfamiliar with the frozen scope might otherwise flag.

## Bottom line

Zero test failures across 80 tests, all NFR targets met with large margins against the real dataset, no SQL-injection surface found, auth/CSRF/cookie configuration all empirically verified rather than assumed, and the import pipeline's core atomicity guarantee holds under a simulated crash. The one real bug this phase surfaced (CSV encoding) would have silently blocked every real-world import — it is now fixed and verified against the actual file. The platform is in good shape for the MVP-core workspaces it covers; the items above are the honest list of what's still open before this should handle real production data at scale.

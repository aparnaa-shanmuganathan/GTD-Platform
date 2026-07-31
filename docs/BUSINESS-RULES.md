# Consolidated Business Rules (Phase 12 — SRS Section 7 closeout)

**Scope:** every business rule this platform enforces, aggregated from each workspace/service's own scattered decisions across CLAUDE.md §12 (Phases 0–11) into one place, organized by concern rather than restated from scratch. Each rule names its owning component and, where useful, the rationale recorded when it was decided. This is a consolidation, not a new specification — where this document and a component's own Javadoc disagree, the code and its Javadoc are authoritative and this document has drifted.

## 1. Unknown/unspecified categorical data (AWS-UNK-001)

- **Organization, Attack Type, Weapon Type, Target Type:** a blank/unresolvable value at import resolves to that dimension's seeded `is_unknown` row (a real row, never a nullable FK — CLAUDE.md §7). Named-entity aggregates (rankings, "distinct X" counts) **exclude** this row at query time. Owner: `RankingAndBreakdownService` (all six dimension rankings, refactored to one generic method in Phase 8), `KpiAggregationService` (SAV-006 Active Organizations).
- **Country/Region:** a different mechanism — **staging-level exclusion**, not an `is_unknown` fallback. A row with a blank/unresolvable country, or a new country whose `region_txt` doesn't match one of the 12 seeded canonical regions, is **rejected outright** (`UNRESOLVABLE_COUNTRY_OR_REGION`) at import time, never normalized to "Unknown." Consequence: every `fact_incident.country_id` is always a real, named country, so SAV-005 (Countries Affected) needs no exclusion logic at all — there is nothing to exclude. Owner: `DimensionResolver` (`service.imports`).
- **Region assignment is fixed at first resolution.** A country's region is only read from incoming row data when *creating* a new country; an existing country's stored region is never re-derived from a later row that happens to disagree, specifically so `fact_incident`'s region-consistency trigger can never be violated by a normalization bug.
- **Regions are a closed set.** A new/unmatched region name never auto-creates a region row — it causes the whole row to be rejected via the country-resolution path above, unlike Organization/Attack/Weapon/Target, which do create new rows for new non-blank values.

## 2. Casualty counts (nkill/nwound)

- **Never coalesced to zero** at the per-row level, enforced at three layers: the `CHECK (... >= 0)` constraint, service-query level, and `SUM()`'s own standard-SQL null-skipping behavior in every `mv_*_year_agg` materialized view (a group where every incident's casualty count is unknown correctly yields a NULL aggregate, not zero). CLAUDE.md §7/§8.
- **Aggregate-level display coalescing is a distinct, narrower rule**, applied only where a wholly-null aggregate would otherwise be undisplayable: `KpiAggregationService`'s `orZero` helper for `kpiFatalities`/`kpiInjuries`, and `CasualtyAggregationService`'s Total Casualties KPI (`COALESCE(SUM(nkill),0) + COALESCE(SUM(nwound),0)`) follow the identical convention. This is "display a 0" for a genuinely-all-unknown group, never "treat an individual unknown row as zero."
- **Parsing is lenient at import**: `"5"` and pandas-export-style `"5.0"` both parse to `5`; `"NaN"`/blank both mean unknown (`null`). Only genuinely non-numeric text or a negative value rejects the row (`ERR-VALIDATION-002`). Owner: `service.imports` row normalization.
- **Severity Classification (SAV item)** uses fixed band thresholds — `LOW_MAX=0`, `MODERATE_MAX=10`, `HIGH_MAX=50` — user-approved defaults, explicitly deferred to CONFIG rather than made admin-configurable in Phase 8. Owner: `SeverityBandClassifier` (pure function; the SQL aggregate version interpolates the same constants, never redefines them).

## 3. Import lifecycle and semantics (AWS-IMPORT-001)

- **REPLACE vs APPEND is row-level upsert**, not wholesale table replacement — a direct consequence of `fact_incident`'s single-column `eventid` PK meaning the table holds one current row per event, never a historical snapshot per dataset version. REPLACE: `INSERT ... ON CONFLICT (eventid) DO UPDATE`. APPEND: `INSERT ... ON CONFLICT (eventid) DO NOTHING`, logging the incoming duplicate as rejected (`DUPLICATE_EVENTID_APPEND_SKIPPED`). **Neither mode ever deletes a row** absent from the new file.
- **Only the primary classification is imported** — `attacktype1`/`weaptype1`/`targtype1`/`gname` — a real, disclosed limitation (a secondary classification in the source data is silently dropped, not stored anywhere).
- **Confirmation is an explicit, separate step**, never automatic. The async staging/normalization job always ends in `AWAITING_CONFIRMATION`; an admin must call `/api/import/confirm` to activate the new dataset version, giving a checkpoint to review rejection counts first.
- **Session-independence means the background job's lifecycle is decoupled from any one HTTP session, not that import is unauthenticated.** Every `/api/import/**` endpoint requires an authenticated admin session (the one exception to the post-Phase-7 public-viewing access model — see §5 below); the actual decoupling is that staging/normalization runs on a dedicated executor thread, with all progress persisted in `import_batch`.
- **Import concurrency is a single PostgreSQL advisory lock** — one import at a time, platform-wide, independent of REPORT's own `REPEATABLE READ` isolation.
- **Dimension row pruning after a REPLACE that changes an existing row's classification is formally deferred, not implemented** — see the dedicated decision in §8 below.

## 4. Report generation and immutability

- **`generated_report` is append-only** (a DB trigger, `fn_reject_table_mutation()`, not an application convention) — no update or delete is possible even by a future ORM bug or raw SQL session.
- **A failed generation still leaves a visible, permanent record**: `dataset_version_id` and `filter_snapshot` are resolved first (cheap, rarely fails); if rendering/storage then fails, a `FAILED` row (no `file_path`) is saved before the error propagates to the caller.
- **No embedded chart images in PDF output** — trend/ranking/breakdown figures render as CSS-only proportional bars, and the PDF uses built-in PDF fonts (Helvetica), not the vendored IBM Plex webfonts. A disclosed, deliberate trade-off (no headless-browser/rasterization capability exists server-side), not a silently-dropped feature.
- **REPORT composes existing services' figures rather than recomputing any of them** — `ReportContentService` calls SVC-AGG/SVC-TREND/INSIGHT/`RankingAndBreakdownService` exactly as the Executive Workspace does, so a report's numbers can never drift from what the live dashboard shows for the same filter.

## 5. Access model (post-Phase 7 amendment, supersedes the original Phase 2 default)

- **Every analytical workspace is publicly viewable, no login required.** Authentication gates exactly two categories of action: importing a dataset (`/api/import/**`) and the two genuinely state-changing Administration/CONFIG actions (`POST /api/config/save`, `POST /api/administration/refresh-views`, Phase 10). Every other endpoint — including Data Quality Center, Audit & Activity Log, and System Health Dashboard, which the original spec might have implied were "admin" screens — is `permitAll()`, since viewing them changes nothing.
- **CSRF protection stays on everywhere**, including the public endpoints — this is a state-changing-request protection, not a login-wall, so removing the login requirement did not remove it.
- **Single administrator role, no RBAC, no external auth providers** — a frozen V1 scope decision (CLAUDE.md §2/§11), not an oversight.

## 6. Shared Analytical Vocabulary computation ownership (SAV-001–017, CLAUDE.md §6)

Exactly one computation owner per item, enforced by convention (no shared base class forces this — verified by inspection, not by a compiler):

| SAV item | Owner |
|---|---|
| Incident, Fatalities, Injuries, Success Rate, Total Casualties | `KpiAggregationService` (SVC-AGG) |
| Attack Density, Distinct Entity Count (Countries Affected, Active Organizations) | `KpiAggregationService` (SVC-AGG), with AWS-UNK-001 exclusion where applicable (§1 above) |
| Avg Fatalities/Injuries per Incident | `RankingAndBreakdownService.weaponCasualtySeverityBreakdown` (Weapon Workspace) — the one ranking/breakdown query that computes `AVG()` instead of `COUNT()` |
| Growth Rate | `GrowthRateCalculator` — latest year vs. trailing-up-to-5-year average, reused as-is by SVC-AGG (KPI trend deltas) and INSIGHT (attack-type deviation callout) |
| Moving Average, Trend Slope, Percentile Ranking, Correlation, Outlier | **Exclusively** SVC-STAT (`service.stat`'s five pure calculator classes) — CLAUDE.md §6 explicitly reserves these to the Statistical Workspace; INSIGHT elsewhere uses SVC-TREND's simpler Growth Rate deviation instead, never these five |
| Severity Classification | `SeverityBandClassifier` (Casualty Workspace) |

**Modeling conventions that apply across all of the above:**
- Moving Average's window is **positional** (up to N most recent map entries), not calendar-based — a year with zero incidents never appears in a yearly series sourced from `fact_incident` (nothing to `GROUP BY`), so a calendar window could silently skip a real gap year.
- Percentile Ranking's tie convention is "mean percentile rank" (`(countBelow + 0.5*countEqual) / total * 100`) — chosen specifically so a distribution's own median lands at (or very near) the 50th percentile regardless of ties.
- Correlation aligns two series to their **common years only** — a year present in one series but not the other (because `SUM()` over an all-NULL group produces no row at all) is simply excluded from the correlation input, not treated as a zero.
- Distinct-count/mode-based metrics are **Derived Metrics**, never Required Measures (CLAUDE.md §8) — a documented category distinction, not a numbering convention.

## 7. Date-range filtering granularity

Every workspace's date-range filter operates at **YEAR granularity** (`fact_incident.event_year`), never day/month — `event_month`/`event_day` are frequently `null` under GTD's own "unknown month/day" convention, so filtering at finer precision would silently and incorrectly exclude legitimate rows. This applies uniformly across `FactIncidentFilterSqlBuilder` (direct `fact_incident` queries) and `YearRangeSqlHelper` (materialized-view queries) — the two single-owner SQL-translation points for the canonical filter DTO (CAC-UI-003).

## 8. Dimension row pruning after REPLACE-mode imports — final decision

**Formally deferred, not implemented**, as of Phase 12. This closes CLAUDE.md §13 open item 2 with a written decision rather than leaving it "flagged but undrafted."

**Rationale:** row-level upsert semantics (§3 above) mean a dimension row can only become orphaned in one narrow scenario — a REPLACE-mode re-import reclassifying an *existing* fact row from one dimension value to another (e.g. an incident's attack type changing between releases of the source data), leaving the old dimension row with zero referencing fact rows. There is no scenario in this platform's actual import model where an entire file re-import bulk-deletes fact data and stampedes many dimension rows into orphanhood at once — nothing is ever deleted by either REPLACE or APPEND. Given that:

1. **Impact is genuinely small.** An orphaned dimension row is inert (excluded from every ranking/aggregate that already filters to rows actually referenced by `fact_incident` via a join), not wrong — it just sits unused in `dim_organization`/`dim_attack_type`/etc. until pruned.
2. **A pruning job is a real, if modest, engineering cost**: identifying zero-reference rows correctly requires care around the seeded `is_unknown` rows (which must never be pruned even if transiently unreferenced) and around any in-flight import holding a reference.
3. **No user-facing symptom has ever been observed or reported** from this gap across Phases 3–11's real 181,691-row import testing.

**Decision:** leave unimplemented for V1. If a future phase or real deployment observes dimension tables accumulating meaningfully many stale rows (worth checking via a simple `NOT EXISTS` query against each dimension table periodically), implement a scheduled or admin-triggered cleanup job scoped to exactly the orphaned-row case above, explicitly excluding every `is_unknown` row regardless of reference count.

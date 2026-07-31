# Requirements Traceability Matrix (Phase 12)

**Method:** mechanically aggregated from the requirement-ID tags actually present in this codebase's Javadoc/comments (CLAUDE.md §5's taxonomy), not hand-assembled from memory or intent. Where tagging was inconsistent or incomplete across Phases 0–11, that gap is recorded here explicitly rather than smoothed over — per this phase's own instruction, fabricated traceability is worse than a disclosed gap.

## A. Workspaces → business goal

Every workspace traces to CLAUDE.md §1's overall goal ("interactive dashboards, geospatial visualization, statistical analysis, dynamic filtering, drill-down exploration, executive reporting") specialized to its own analytical dimension. One `ADC-<PREFIX>-001` tag per workspace controller, each documented as implementing "CAC-AN-00x" shared response components per CLAUDE.md §9.

| Workspace | §5 prefix | Code's actual ADC tag | Controller | Traces to |
|---|---|---|---|---|
| Executive | EXEC | `ADC-EXEC-001` | `ExecutiveController` | Cross-cutting KPI/trend/ranking overview — the platform's default landing analytical view |
| Country | COUNTRY | `ADC-COUNTRY-001` | `CountryController` | Per-country ranking, 2–4-country comparison, single-country drill-down |
| Region | REGION | `ADC-REGION-001` | `RegionController` | Regional rollups + member-country breakdown |
| Organization | ORG | `ADC-ORGANIZATION-001` ⚠ | `OrganizationController` | Organization-level ranking, unattributed-incident KPI, drill-down |
| Attack | ATTACK | `ADC-ATTACK-001` | `AttackController` | Attack-type prevalence + success/failure trend |
| Weapon | WEAPON | `ADC-WEAPON-001` | `WeaponController` | Weapon-type usage + casualty-severity linkage |
| Target | TARGET | `ADC-TARGET-001` | `TargetController` | Target-category ranking |
| Casualty | CASUALTY | `ADC-CASUALTY-001` | `CasualtyController` | Total casualties, severity-band distribution |
| Statistical | STAT | `ADC-STAT-001` | `StatisticalController` | SVC-STAT's five exclusive statistics (§C below) |
| Geospatial (MAP) | MAP | `ADC-MAP-001` | `GeospatialController` | Point/cluster/heatmap/choropleth/timeline map |
| Report | REPORT-WS | `ADC-REPORT-001` ⚠ | `ReportController` | PDF/CSV report generation + history |
| Administration | ADMIN | `ADC-ADMIN-001` | `AdministrationController` | Import monitoring + consolidated CONFIG |
| Data Quality Center | DQ | `ADC-DQ-001` | `DataQualityController` | Import batch/rejection review |
| Audit & Activity Log | AUDIT | `ADC-AUDIT-001` | `AuditController` | Queryable, append-only `audit_log` view |
| System Health Dashboard | HEALTH | `ADC-HEALTH-001` | `HealthController` | DB/dataset/MV-freshness/offline-verification status |

**⚠ Two disclosed naming drifts, harmless in practice but worth fixing if this taxonomy is ever mechanically parsed:**
- **ORG vs ORGANIZATION** — CLAUDE.md §5 names the prefix `ORG`; every actual tag in code (`ADC-ORGANIZATION-001`, `ERR-ORGANIZATION-001/002`) spells it out in full instead. Consistent *within* the codebase, just not matching §5's abbreviation.
- **REPORT-WS vs REPORT** — §5 deliberately named the workspace prefix `REPORT-WS` specifically to disambiguate it from the `REPORT` *service* prefix (also in §5). The code's actual tag, `ADC-REPORT-001`, drops the `-WS` and is textually identical to what the REPORT service itself would use — the disambiguation §5 intended never actually made it into the tag. No real collision occurred in practice (the service doesn't happen to use an `ADC-REPORT-001`-shaped tag), but the intended distinction is not enforced by the tags as written.

## B. Cross-cutting services → business goal

| Service | §5 prefix | Owning class | Traces to |
|---|---|---|---|
| SVC-AGG | SVC-AGG | `KpiAggregationService` | Executive Workspace's global KPIs, SAV-001–006 |
| SVC-TREND | SVC-TREND | `TrendService` | Incidents-by-year and related trend series, reused by Attack's prevalence chart, INSIGHT, Statistical's data source |
| SVC-STAT | SVC-STAT | `StatisticalAnalysisService` + 5 calculators (`service.stat`) | Statistical Workspace's 5 exclusive statistics — see §C |
| SVC-GEO | SVC-GEO | `GeospatialQueryService`, `SpatialPolygonFilter` | Geospatial Workspace's point/choropleth/polygon-filter queries |
| SVC-COMPARE | SVC-COMPARE | `CompareService` | Country Workspace's compare (2–4) and drill-down (1) |
| INSIGHT | INSIGHT | `InsightService` | Growth-rate deviation + ranking-delta callouts (CAC-AN-005) |
| REPORT | REPORT | `ReportGenerationService`, `ReportContentService` | On-demand PDF/CSV report generation |
| SEARCH | SEARCH | `SearchService` | Cross-workspace/entity/quick-action global search |
| CONFIG | CONFIG | `PlatformConfigService` | Consolidated platform settings (session timeout, import mode, MV auto-refresh, notifications) |

`RankingAndBreakdownService` is a tenth de facto cross-cutting component (all six dimension rankings, generic since Phase 8) but was never assigned its own §5 prefix — it is reused by name across the A. table above rather than tagged with a dedicated ID. Recorded here as a gap, not fabricated a new ID for it.

## C. Shared Analytical Vocabulary (SAV-001–017, CLAUDE.md §6)

**This is the traceability matrix's most significant finding: CLAUDE.md §6 itself names only 15 items under a "SAV-001–017" header (17 numbers), and the codebase's actual usage does not fill the range contiguously.** Neither gap is a code defect — both predate any implementation and are recorded here rather than papered over.

| SAV # | CLAUDE.md §6 name | Code owner | Status |
|---|---|---|---|
| 001 | Incident | `KpiAggregationService`, `TrendService` | tagged |
| 002 | Fatalities | `KpiAggregationService` | tagged |
| 003 | Injuries | `KpiAggregationService` | tagged |
| 004 | Success Rate | `KpiAggregationService` | tagged |
| 005 | Attack Density | `KpiAggregationService` (KPI's own label reads "Countries affected") | tagged, **label mismatch** — §6 calls this "Attack Density," the code's KPI card text calls it "Countries affected" (which is actually closer to §6's separately-named "Distinct Entity Count"/SAV-006's own description). Worth a naming reconciliation pass, not a computation error — the underlying figure is correct for what it displays, just possibly cross-labeled against SAV-005 vs SAV-006 |
| 006 | Distinct Entity Count | `KpiAggregationService` ("Active organizations") | tagged |
| 007 | Avg Fatalities/Injuries per Incident | `CasualtyAggregationService` | tagged — one ID covers two KPI cards (avg fatalities, avg injuries) |
| 008 | Growth Rate | `GrowthRateCalculator` | tagged |
| 009 | *(unnamed in §6)* | — | **never used anywhere in code** |
| 010 | Moving Average *(positional)* | `MovingAverageCalculator` | tagged as SAV-011 in code, not SAV-010 — see note below |
| 011 | Trend Slope *(positional)* | `TrendSlopeCalculator` | tagged as SAV-010 in code — **positions 010/011 appear swapped relative to §6's naming order** |
| 012 | Percentile Ranking *(positional)* | `PercentileRankingCalculator` | tagged as SAV-013 |
| 013 | Total Casualties *(positional)* | `CasualtyAggregationService` | tagged as SAV-012 |
| 014 | *(unnamed in §6)* | — | never used |
| 015 | *(unnamed in §6)* | — | never used |
| 016 | Correlation *(positional)* | `CorrelationCalculator` | tagged as SAV-016 |
| 017 | Outlier *(positional)* | `OutlierDetector` | tagged as SAV-017 |
| — | Severity Classification | `SeverityBandClassifier` | tagged only as bare **"SAV \"Severity Classification\""** — **no numeric ID assigned at all**, in code or in §6's list |

**Root cause:** §6's own prose lists exactly 15 comma-separated names but headlines them "SAV-001–017" — a 17-slot range for a 15-item list, with no explicit number-to-name mapping ever written down. `workspace/statistical/package-info.java` is the only place in the codebase that pairs specific numbers to the five SVC-STAT names, and it did so via position in its own sentence rather than against a canonical numbered list — which is exactly how 010/011 ended up transposed relative to a reader's likely first guess. **This is a specification gap inherited from before any code was written, not something a later phase corrutped.** Recommendation if this matters going forward: amend CLAUDE.md §6 with an explicit `SAV-001: Incident` / `SAV-002: Fatalities` / ... numbered list (assigning 009/014/015 to Severity Classification and two of the reordered ones, or retiring them), then align the four transposed code tags to match.

## D. Shared UI components — CAC-AN-00x/CAC-UI-003 supersede §5's COMP-* naming

CLAUDE.md §5 names `COMP-UI`/`COMP-AN`/`COMP-INT` as the shared-component prefix family. **Zero occurrences of any `COMP-*` tag exist anywhere in `src/main/java`.** What was actually built and consistently tagged, starting in Phase 2, is a different, more specific scheme from CLAUDE.md §9:

| Component | Tag | Occurrences | Introduced |
|---|---|---|---|
| Canonical filter request | `CAC-UI-003` | 12 | Phase 2 |
| KPI card | `CAC-AN-001` | 10 | Phase 2 |
| Ranking | `CAC-AN-002` | 12 | Phase 2 (gained `entityId` field, Phase 5) |
| Chart | `CAC-AN-003` | 15 | Phase 2 |
| Map | `CAC-AN-004` | 2 | Phase 9 (deferred from Phase 2) |
| Insights | `CAC-AN-005` | 3 | Phase 2 |
| Statistical Summary | `CAC-AN-006` | 4 | Phase 9 (deferred from Phase 2) |

**Conclusion:** `CAC-*` is the real, exclusively-used shared-component ID scheme; §5's `COMP-*` naming was superseded before any code existed and should be treated as historical/retired rather than "not yet implemented."

## E. AWM / AWS-* (architecture/workflow rules)

| ID | Occurrences | Traces to |
|---|---|---|
| AWS-UNK-001 | 16 files | Unknown-value handling, §A/§C in `docs/BUSINESS-RULES.md` §1 |
| AWS-IMPORT-001 | 8 files | Session-independent background import, `docs/BUSINESS-RULES.md` §3 |
| AWM (bare prefix) | 0 | **Never used.** No workflow rule was ever tagged with a bare `AWM-*` id; every workflow rule that exists is one of the two `AWS-*` ids above instead. |

## F. NFR prefixes (PERF, SEC, REL, TIME, USE, MAINT)

**Zero occurrences of any of these six prefixes as a code-level tag, anywhere in `src/main/java`, across all 11 phases.** Every non-functional requirement verification that has ever been performed on this platform exists as narrative prose with measured numbers in `docs/PHASE-7-HARDENING-REPORT.md` (MVP core, Phase 7) and `docs/NFR-VERIFICATION-REPORT.md` (full system, Phase 12) — never as an in-code `PERF-001`-style comment. This is recorded here as a genuine gap: a future phase wanting compiler/grep-level traceability from an NFR target to the code/test that verifies it would need to retrofit these six prefixes into test class Javadoc; today that link exists only in these two documents' prose.

## G. Full HTTP API surface (reference)

43 distinct endpoints across 19 controllers, all under the standard `success`/`data`/`meta`/`error` envelope (CLAUDE.md §9) except the five documented exemptions (`GET`/`POST /login`, `POST /logout`, `POST /api/report/download`, `GET /api/geospatial/boundaries`).

**Cross-cutting:** `GET /`, `GET /login`, `POST /login`, `POST /logout`, `POST /api/import/{upload,status,confirm}` (authenticated), `POST /api/search`.

**Per workspace** (`GET /workspace/<name>` + its own `POST /api/<name>/*` data endpoints): executive (`dashboard`, `report`), country (`ranking`, `compare`, `drilldown`), region (`ranking`, `drilldown`), organization (`ranking`, `kpis`, `drilldown`), attack (`dashboard`), weapon (`dashboard`), target (`dashboard`), casualty (`dashboard`), statistical (`summary`), geospatial (`points`, plus `GET /api/geospatial/boundaries`), report (`generate`, `history`, `download`), administration (`config` view, `config/save` [authenticated], `refresh-views` [authenticated], `import-batches`), dataquality (`batches`, `batch-detail`), audit (`entries`), health (`check`).

Only `/api/import/**`, `/api/config/save`, and `/api/administration/refresh-views` require authentication — every other endpoint above is `permitAll()` per the post-Phase-7 access-model amendment (CLAUDE.md §12, and `docs/BUSINESS-RULES.md` §5).

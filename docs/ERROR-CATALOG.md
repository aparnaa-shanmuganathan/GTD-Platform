# Error Catalog (Phase 12 — final, consolidated)

**Source of truth:** `com.gtd.platform.shared.exception.ErrorCode` — this document is a mechanically-derived, human-readable rendering of that enum as it stands after Phase 10 (no error codes were added in Phase 11, a packaging-only phase). Every error a caller of this API can ever receive is one of the codes below, delivered through the standard envelope (CLAUDE.md §9) via `GlobalExceptionHandler`. **Never renumber or repurpose a shipped code** — `ErrorCode`'s own Javadoc states this, and this catalog inherits the same rule; a new phase appends new codes rather than reusing a retired one.

## IMPORT — file upload / staging lifecycle (AWS-IMPORT-001)

| Code | HTTP | Message |
|---|---|---|
| `ERR-IMPORT-001` | 400 Bad Request | Uploaded file is not a valid CSV |
| `ERR-IMPORT-002` | 413 Payload Too Large | Uploaded file exceeds the maximum allowed size |
| `ERR-IMPORT-003` | 409 Conflict | An import is already in progress (advisory lock held) |
| `ERR-IMPORT-004` | 404 Not Found | Import batch not found |
| `ERR-IMPORT-005` | 409 Conflict | Import batch is not in a state that allows this action |

## VALIDATION — per-row staging validation/cleansing

| Code | HTTP | Message |
|---|---|---|
| `ERR-VALIDATION-001` | 422 Unprocessable Entity | Row failed required-field validation |
| `ERR-VALIDATION-002` | 422 Unprocessable Entity | Row contains a non-numeric casualty count (nkill/nwound) |
| `ERR-VALIDATION-003` | 422 Unprocessable Entity | Row contains an unparseable incident date |
| `ERR-VALIDATION-004` | 409 Conflict | Duplicate eventid detected within the same batch |

## DATA — schema/constraint-level violations surfaced to callers

| Code | HTTP | Message |
|---|---|---|
| `ERR-DATA-001` | 409 Conflict | Requested dataset_version is not the currently active version |
| `ERR-DATA-002` | 500 Internal Server Error | fact_incident.region_id is inconsistent with dim_country.region_id |

## AUTH — session-based authentication (Phase 2)

| Code | HTTP | Message |
|---|---|---|
| `ERR-AUTH-001` | 401 Unauthorized | Authentication is required to access this resource |
| `ERR-AUTH-002` | 403 Forbidden | Access is denied |
| `ERR-AUTH-003` | 401 Unauthorized | Invalid username or password |

## REQUEST — malformed/invalid API request bodies (distinct from VALIDATION, which is CSV-row-specific)

| Code | HTTP | Message |
|---|---|---|
| `ERR-REQUEST-001` | 400 Bad Request | Request body failed validation |
| `ERR-REQUEST-002` | 400 Bad Request | Request body is malformed or unreadable |

## REPORT — report generation

| Code | HTTP | Message |
|---|---|---|
| `ERR-REPORT-001` | 501 Not Implemented | Report generation is not yet implemented — **retired in practice**: this was Phase 4's stub for the not-yet-built REPORT service; no code path has thrown it since Phase 6's real implementation shipped. Kept defined, never repurposed, per the no-renumbering rule. |
| `ERR-REPORT-002` | 409 Conflict | No active dataset version - import and confirm data before generating a report |
| `ERR-REPORT-003` | 500 Internal Server Error | Report generation failed |
| `ERR-REPORT-004` | 404 Not Found | Report not found |
| `ERR-REPORT-005` | 409 Conflict | This report has no downloadable file (a failed or incomplete generation) |

## COMPARE — SVC-COMPARE country comparison / drill-down (Phase 5)

| Code | HTTP | Message |
|---|---|---|
| `ERR-COMPARE-001` | 400 Bad Request | Country comparison requires between 2 and 4 countryIds |
| `ERR-COMPARE-002` | 400 Bad Request | Country drill-down requires exactly 1 countryId |
| `ERR-COMPARE-003` | 404 Not Found | Requested country id does not exist |

## REGION — Region Workspace drill-down (Phase 8)

| Code | HTTP | Message |
|---|---|---|
| `ERR-REGION-001` | 400 Bad Request | Region drill-down requires exactly 1 regionId |
| `ERR-REGION-002` | 404 Not Found | Requested region id does not exist |

## ORGANIZATION — Organization Workspace drill-down (Phase 8)

| Code | HTTP | Message |
|---|---|---|
| `ERR-ORGANIZATION-001` | 400 Bad Request | Organization drill-down requires exactly 1 organizationId |
| `ERR-ORGANIZATION-002` | 404 Not Found | Requested organization id does not exist |

## CONFIG — consolidated platform configuration (Phase 10)

| Code | HTTP | Message |
|---|---|---|
| `ERR-CONFIG-001` | 400 Bad Request | Invalid configuration value |

## SYSTEM — catch-all

| Code | HTTP | Message |
|---|---|---|
| `ERR-SYSTEM-001` | 500 Internal Server Error | Unexpected internal error |

## Consolidation notes (Phase 12)

- **32 codes across 11 categories**, spanning Phases 1 (drafted), 2 (wired into `GlobalExceptionHandler`), 4, 5, 6, 8, and 10 (new categories added as each phase needed them).
- **Deliberately no error code exists for `/api/geospatial/boundaries`, `/api/search`, `/api/audit/entries`, `/api/health/check`, or the Data Quality Center endpoints** — every one of these is a purely read-only query with no validated-request-shape or business-rule-violation path narrower than the generic `REQUEST`/`SYSTEM` categories above, so no dedicated category was warranted for them (consistent with the "one category per real business-rule-violation surface" pattern every existing category above follows — a category was added only when a workspace/service actually needed to distinguish its own failure modes from the generic ones).
- **No Category exists for the Statistical, Attack, Weapon, Target, or Casualty Workspaces** — all five are single-view (no drill-down, no comparison), so they have no request-shape-validation business rule beyond what `REQUEST`'s two generic codes already cover.
- This document intentionally does not restate `GlobalExceptionHandler`'s own envelope-wrapping logic (CLAUDE.md §9, `shared.api.ApiResponse`/`ApiError`) — that is implementation, not catalog content.

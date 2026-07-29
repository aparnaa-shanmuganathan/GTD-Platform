/**
 * Shared Infrastructure — the platform's API contract layer: the standard
 * response envelope ({@code success}/{@code data}/{@code meta}/{@code error})
 * and the canonical shared-component request/response DTOs from CLAUDE.md
 * section 9 (CAC-UI-003 filter request, CAC-AN-001/002/003/005 response
 * shapes).
 *
 * <p>This package is not one of the eight Shared Infrastructure buckets
 * originally named in CLAUDE.md section 4 (Auth, Logging, Exception
 * Handling, Reporting, DB Access, Security, Validation, File Management).
 * It was added in Phase 2 because none of those eight cleanly fit "the
 * envelope every controller wraps its response in" or "the shared
 * KPI/Ranking/Chart/Insights DTO shapes every workspace populates" - see
 * CLAUDE.md section 12 for the decision in full. {@link com.gtd.platform.shared.exception}
 * depends on this package for {@code ApiResponse}/{@code ApiError} (the
 * envelope), not the other way around.
 */
package com.gtd.platform.shared.api;

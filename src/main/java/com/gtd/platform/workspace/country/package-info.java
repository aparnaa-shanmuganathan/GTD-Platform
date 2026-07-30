/**
 * Country Workspace (COUNTRY) — country-level rankings, side-by-side
 * comparison of up to 4 countries, and time-trend drill-down per country.
 *
 * <p>{@link com.gtd.platform.workspace.country.CountryController} is the
 * only thing this package owns: rankings reuse
 * {@code service.agg.RankingAndBreakdownService} (CAC-AN-002, unmodified
 * query logic from Phase 4 beyond the {@code entityId} addition), and
 * comparison/drill-down reuse {@code service.compare.CompareService}
 * (SVC-COMPARE) - neither lives here, so a future workspace can reuse them
 * too without depending on this workspace package, per CLAUDE.md section 4's
 * "never let a workspace package depend on another workspace package" rule.
 */
package com.gtd.platform.workspace.country;

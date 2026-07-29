/**
 * SVC-TREND — incidents-by-year trend series and Growth Rate (SAV-008), the
 * platform's single Growth Rate computation owner (CLAUDE.md section 6).
 * Not one of the four "Cross-Cutting Platform Services" originally named in
 * CLAUDE.md section 4 (IMPORT/SEARCH/INSIGHT/CONFIG) - added in Phase 4
 * following the same {@code service.*} convention, since it is reused
 * across workspaces (Executive in this phase, Country from Phase 5 per
 * {@code docs/BUILD-PHASES.md}) and so cannot live inside any single
 * {@code workspace.*} package without violating the "no workspace depends
 * on another workspace" rule. See CLAUDE.md section 12.
 */
package com.gtd.platform.service.trend;

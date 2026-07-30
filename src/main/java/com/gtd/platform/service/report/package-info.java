/**
 * REPORT — on-demand PDF/CSV report generation (CLAUDE.md sections 4, 8).
 *
 * <p>One of the two services explicitly permitted to break strict
 * statelessness (section 8: "REPORT persists artifacts"). Composes
 * {@code service.agg}/{@code service.trend}/{@code service.insight} for the
 * actual analytical figures - matching what the Executive Workspace shows -
 * rather than recomputing any of them; its own distinct responsibilities are
 * gathering that data under {@code REPEATABLE READ} isolation
 * ({@link com.gtd.platform.service.report.ReportContentService}, independent
 * of the IMPORT advisory lock), rendering it to PDF/CSV, and persisting the
 * immutable {@code generated_report} record.
 */
package com.gtd.platform.service.report;

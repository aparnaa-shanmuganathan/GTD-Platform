/**
 * Executive Workspace (EXEC) — global KPIs, top-country rankings, and executive-level insights.
 *
 * <p>{@link com.gtd.platform.workspace.executive.ExecutiveController} is the
 * only thing this package owns: the actual computations
 * ({@code service.agg.KpiAggregationService}/{@code RankingAndBreakdownService},
 * {@code service.trend.TrendService}, {@code service.insight.InsightService})
 * live in cross-workspace {@code service.*} packages so Phase 5's Country
 * Workspace can reuse them without depending on this workspace package,
 * per CLAUDE.md section 4's "never let a workspace package depend on
 * another workspace package" rule.
 */
package com.gtd.platform.workspace.executive;

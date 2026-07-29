/**
 * INSIGHT cross-cutting service — narrative insight generation (growth-rate deviation callouts, ranking deltas).
 *
 * <p>{@link com.gtd.platform.service.insight.InsightService} generates
 * CAC-AN-005 insight items for the Executive Workspace (Phase 4): real
 * computation against {@code mv_attacktype_year_agg}/{@code mv_country_year_agg},
 * reusing {@code service.trend.GrowthRateCalculator} (SAV-008's single
 * computation owner) rather than reimplementing growth rate here.
 */
package com.gtd.platform.service.insight;

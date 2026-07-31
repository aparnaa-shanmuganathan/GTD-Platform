package com.gtd.platform.workspace.casualty;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.KpiResponse;
import java.util.List;

/**
 * Casualty Workspace's single-view dashboard shape (Phase 8) - no ranking widget, unlike every
 * other Phase 8 workspace: the spec names no dimension to rank by, and the severity-band
 * distribution chart already *is* this workspace's primary breakdown.
 *
 * @param kpis                SAV-001..006 (CAC-AN-001, reused from SVC-AGG unmodified) plus Total Casualties
 *                            and the two per-incident averages (CasualtyAggregationService) - one flat,
 *                            merged list so the shared KPI-card rendering (executive.js's pattern) needs
 *                            no special-casing for where each card's number came from
 * @param severityBandChart   incident counts per severity band (CAC-AN-003, SAV "Severity Classification")
 */
public record CasualtyDashboardResponse(
        List<KpiResponse> kpis,
        ChartResponse severityBandChart
) {
}

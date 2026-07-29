package com.gtd.platform.workspace.executive;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.InsightsResponse;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.api.RankingResponse;
import java.util.List;

/**
 * Everything the Executive Workspace's initial page render and its
 * filter-driven re-query ({@code POST /api/executive/dashboard}) both need,
 * bundled into one response so a filter change is a single round trip
 * (relevant to the &le;2s filter-apply NFR, CLAUDE.md section 10). Specific
 * to this workspace, not a CAC-* shared shape - lives alongside the
 * controller rather than in {@code shared.api}.
 */
public record ExecutiveDashboardResponse(
        List<KpiResponse> kpis,
        RankingResponse countryRanking,
        ChartResponse trendChart,
        ChartResponse attackChart,
        ChartResponse weaponChart,
        ChartResponse targetChart,
        InsightsResponse insights
) {
}

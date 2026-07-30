package com.gtd.platform.service.report;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.InsightsResponse;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.api.RankingResponse;
import java.util.List;

/**
 * Everything a generated report needs to render (PDF or CSV) - the same
 * KPIs/ranking/trend/breakdown/insights shape the Executive Workspace's
 * dashboard shows (CLAUDE.md section 12, Phase 6 prompt), gathered fresh at
 * generation time by {@link ReportContentService} rather than reusing
 * {@code workspace.executive.ExecutiveDashboardResponse} - a cross-cutting
 * service depending on a specific workspace's response type would be an odd
 * layering inversion, so this is its own, REPORT-owned copy of the shape.
 */
public record ReportContent(
        List<KpiResponse> kpis,
        RankingResponse countryRanking,
        ChartResponse trendChart,
        ChartResponse attackChart,
        ChartResponse weaponChart,
        ChartResponse targetChart,
        InsightsResponse insights
) {
}

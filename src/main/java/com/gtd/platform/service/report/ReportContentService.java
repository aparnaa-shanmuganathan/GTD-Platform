package com.gtd.platform.service.report;

import com.gtd.platform.service.agg.KpiAggregationService;
import com.gtd.platform.service.agg.RankingAndBreakdownService;
import com.gtd.platform.service.insight.InsightService;
import com.gtd.platform.service.trend.TrendService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers a {@link ReportContent} snapshot under {@code REPEATABLE READ}
 * isolation (CLAUDE.md section 8: "Report generation is isolated via
 * REPEATABLE READ, independent of the IMPORT advisory lock") - a single
 * transaction spanning every underlying query, so a concurrent import
 * committing mid-generation can never make one chart reflect newer data
 * than another within the same report. Deliberately never touches
 * {@code ImportAdvisoryLockService}: the "independent of the IMPORT
 * advisory lock" half of that rule is satisfied simply by not calling it -
 * report generation is never blocked by, and never blocks, an in-flight
 * import.
 *
 * <p>A separate bean from {@link ReportGenerationService} for the same
 * reason {@code ImportOrchestrationService}/{@code ImportJobRunner} are two
 * beans (Phase 3, CLAUDE.md section 12): {@code @Transactional} only takes
 * effect through Spring's AOP proxy, and {@code ReportGenerationService}
 * calling this method on itself (self-invocation) would silently run it
 * with no transaction/isolation guarantee at all.
 */
@Service
public class ReportContentService {

    private static final int REPORT_RANKING_LIMIT = 10;

    private final KpiAggregationService kpiAggregationService;
    private final RankingAndBreakdownService rankingAndBreakdownService;
    private final TrendService trendService;
    private final InsightService insightService;

    public ReportContentService(KpiAggregationService kpiAggregationService,
            RankingAndBreakdownService rankingAndBreakdownService, TrendService trendService,
            InsightService insightService) {
        this.kpiAggregationService = kpiAggregationService;
        this.rankingAndBreakdownService = rankingAndBreakdownService;
        this.trendService = trendService;
        this.insightService = insightService;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public ReportContent gather(AnalyticalFilterRequest filter) {
        return new ReportContent(
                kpiAggregationService.computeExecutiveKpis(filter),
                rankingAndBreakdownService.topCountriesByIncidentCount(filter, REPORT_RANKING_LIMIT),
                trendService.computeIncidentsByYearTrend(filter),
                rankingAndBreakdownService.attackTypeBreakdown(filter),
                rankingAndBreakdownService.weaponTypeBreakdown(filter),
                rankingAndBreakdownService.targetTypeBreakdown(filter),
                insightService.generateExecutiveInsights(filter)
        );
    }
}

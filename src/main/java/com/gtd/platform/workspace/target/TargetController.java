package com.gtd.platform.workspace.target;

import com.gtd.platform.service.agg.KpiAggregationService;
import com.gtd.platform.service.agg.RankingAndBreakdownService;
import com.gtd.platform.service.trend.TrendService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimRegionRepository;
import java.security.Principal;
import java.util.Comparator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ADC-TARGET-001: the Target Workspace (Phase 8), the simplest of the six new workspaces.
 * {@code GET /workspace/target} server-renders the initial dashboard against the unfiltered
 * dataset; the filter bar re-queries {@code POST /api/target/dashboard} client-side - the same
 * single-combined-endpoint, no-drill-down shape {@code AttackController}/{@code
 * WeaponController} established.
 *
 * <p>Nothing here is genuinely new: {@code RankingAndBreakdownService.targetTypeRanking} (added
 * alongside the other Phase 8 rankings when {@code queryDirectRanking} was introduced) plus
 * plain {@code computeExecutiveKpis}/{@code computeIncidentsByYearTrend} fully cover
 * "target-category analysis across incidents" with zero new computation.
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - {@code principal} is {@code null} for an anonymous visitor.
 */
@Controller
public class TargetController {

    private static final int TARGET_TYPE_RANKING_LIMIT = 25;

    private final KpiAggregationService kpiAggregationService;
    private final TrendService trendService;
    private final RankingAndBreakdownService rankingAndBreakdownService;
    private final JdbcTemplate jdbcTemplate;
    private final DimRegionRepository regionRepository;
    private final DimAttackTypeRepository attackTypeRepository;

    public TargetController(KpiAggregationService kpiAggregationService, TrendService trendService,
            RankingAndBreakdownService rankingAndBreakdownService, JdbcTemplate jdbcTemplate,
            DimRegionRepository regionRepository, DimAttackTypeRepository attackTypeRepository) {
        this.kpiAggregationService = kpiAggregationService;
        this.trendService = trendService;
        this.rankingAndBreakdownService = rankingAndBreakdownService;
        this.jdbcTemplate = jdbcTemplate;
        this.regionRepository = regionRepository;
        this.attackTypeRepository = attackTypeRepository;
    }

    @GetMapping("/workspace/target")
    public String targetWorkspace(Model model, Principal principal) {
        AnalyticalFilterRequest unfiltered = new AnalyticalFilterRequest();
        TargetDashboardResponse dashboard = buildDashboard(unfiltered);
        YearBounds yearBounds = queryYearBounds();

        model.addAttribute("username", principal != null ? principal.getName() : null);
        model.addAttribute("minYear", yearBounds.minYear());
        model.addAttribute("maxYear", yearBounds.maxYear());
        model.addAttribute("regions", regionRepository.findAll().stream()
                .filter(r -> !r.isUnknown())
                .sorted(Comparator.comparing(r -> r.getName()))
                .toList());
        model.addAttribute("attackTypes", attackTypeRepository.findAll().stream()
                .filter(a -> !a.isUnknown())
                .sorted(Comparator.comparing(a -> a.getName()))
                .toList());
        model.addAttribute("dashboard", dashboard);
        return "target";
    }

    @PostMapping("/api/target/dashboard")
    @ResponseBody
    public ApiResponse<TargetDashboardResponse> dashboard(
            @RequestBody(required = false) AnalyticalFilterRequest filter) {
        AnalyticalFilterRequest effectiveFilter = (filter != null) ? filter : new AnalyticalFilterRequest();
        return ApiResponse.success(buildDashboard(effectiveFilter));
    }

    private TargetDashboardResponse buildDashboard(AnalyticalFilterRequest filter) {
        return new TargetDashboardResponse(
                kpiAggregationService.computeExecutiveKpis(filter),
                rankingAndBreakdownService.targetTypeRanking(filter, TARGET_TYPE_RANKING_LIMIT),
                trendService.computeIncidentsByYearTrend(filter));
    }

    /**
     * Powers the filter bar's date-range presets, anchored on the data's own max year rather than
     * wall-clock time - same reasoning and same one-off-query-not-a-service treatment as {@code
     * ExecutiveController}'s identical helper (Phase 4).
     */
    private YearBounds queryYearBounds() {
        return jdbcTemplate.queryForObject(
                "SELECT MIN(event_year) AS min_year, MAX(event_year) AS max_year FROM fact_incident",
                (rs, rowNum) -> {
                    int min = rs.getInt("min_year");
                    Integer minYear = rs.wasNull() ? null : min;
                    int max = rs.getInt("max_year");
                    Integer maxYear = rs.wasNull() ? null : max;
                    return new YearBounds(minYear, maxYear);
                });
    }

    private record YearBounds(Integer minYear, Integer maxYear) {
    }
}

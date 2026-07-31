package com.gtd.platform.workspace.casualty;

import com.gtd.platform.service.agg.KpiAggregationService;
import com.gtd.platform.service.casualty.CasualtyAggregationService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimRegionRepository;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ADC-CASUALTY-001: the Casualty Workspace (Phase 8) - admin-configurable severity bands
 * (thresholds currently fixed defaults, deferred to Phase 10's CONFIG service per CLAUDE.md
 * section 12's confirmed decision), total casualties, and a statistical summary of casualty
 * distribution (mean/total/band-distribution only - percentile ranking and the rest of
 * SVC-STAT's exclusive statistics, CLAUDE.md section 6, are out of scope here).
 *
 * <p>{@code GET /workspace/casualty} server-renders the initial dashboard against the
 * unfiltered dataset; the filter bar re-queries {@code POST /api/casualty/dashboard}
 * client-side - the same single-combined-endpoint, no-drill-down shape {@code
 * AttackController}/{@code WeaponController}/{@code TargetController} established.
 *
 * <p>Merges {@link KpiAggregationService#computeExecutiveKpis} (SAV-001..006, reused
 * unmodified) with {@link CasualtyAggregationService#computeCasualtyKpis} (the genuinely new
 * pieces) into one flat KPI list - orchestration the controller does directly rather than
 * either service depending on the other.
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - {@code principal} is {@code null} for an anonymous visitor.
 */
@Controller
public class CasualtyController {

    private final KpiAggregationService kpiAggregationService;
    private final CasualtyAggregationService casualtyAggregationService;
    private final JdbcTemplate jdbcTemplate;
    private final DimRegionRepository regionRepository;
    private final DimAttackTypeRepository attackTypeRepository;

    public CasualtyController(KpiAggregationService kpiAggregationService,
            CasualtyAggregationService casualtyAggregationService, JdbcTemplate jdbcTemplate,
            DimRegionRepository regionRepository, DimAttackTypeRepository attackTypeRepository) {
        this.kpiAggregationService = kpiAggregationService;
        this.casualtyAggregationService = casualtyAggregationService;
        this.jdbcTemplate = jdbcTemplate;
        this.regionRepository = regionRepository;
        this.attackTypeRepository = attackTypeRepository;
    }

    @GetMapping("/workspace/casualty")
    public String casualtyWorkspace(Model model, Principal principal) {
        AnalyticalFilterRequest unfiltered = new AnalyticalFilterRequest();
        CasualtyDashboardResponse dashboard = buildDashboard(unfiltered);
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
        return "casualty";
    }

    @PostMapping("/api/casualty/dashboard")
    @ResponseBody
    public ApiResponse<CasualtyDashboardResponse> dashboard(
            @RequestBody(required = false) AnalyticalFilterRequest filter) {
        AnalyticalFilterRequest effectiveFilter = (filter != null) ? filter : new AnalyticalFilterRequest();
        return ApiResponse.success(buildDashboard(effectiveFilter));
    }

    private CasualtyDashboardResponse buildDashboard(AnalyticalFilterRequest filter) {
        List<KpiResponse> kpis = new ArrayList<>(kpiAggregationService.computeExecutiveKpis(filter));
        kpis.addAll(casualtyAggregationService.computeCasualtyKpis(filter));
        return new CasualtyDashboardResponse(kpis, casualtyAggregationService.computeSeverityBandDistribution(filter));
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

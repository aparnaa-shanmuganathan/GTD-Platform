package com.gtd.platform.workspace.statistical;

import com.gtd.platform.service.stat.StatisticalAnalysisService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.api.StatisticalSummaryResponse;
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
 * ADC-STAT-001: the Statistical Workspace (Phase 9) - SVC-STAT's five exclusive statistics
 * (CLAUDE.md section 6), exposed through the CAC-AN-006 statistical summary component ({@link
 * StatisticalSummaryResponse}). {@code GET /workspace/statistical} server-renders the initial
 * summary against the unfiltered dataset; the filter bar re-queries {@code POST
 * /api/statistical/summary} client-side - the same single-combined-endpoint, no-drill-down
 * shape {@code AttackController}/{@code WeaponController}/{@code TargetController}/{@code
 * CasualtyController} already established.
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - {@code principal} is {@code null} for an anonymous visitor.
 */
@Controller
public class StatisticalController {

    private final StatisticalAnalysisService statisticalAnalysisService;
    private final JdbcTemplate jdbcTemplate;
    private final DimRegionRepository regionRepository;
    private final DimAttackTypeRepository attackTypeRepository;

    public StatisticalController(StatisticalAnalysisService statisticalAnalysisService, JdbcTemplate jdbcTemplate,
            DimRegionRepository regionRepository, DimAttackTypeRepository attackTypeRepository) {
        this.statisticalAnalysisService = statisticalAnalysisService;
        this.jdbcTemplate = jdbcTemplate;
        this.regionRepository = regionRepository;
        this.attackTypeRepository = attackTypeRepository;
    }

    @GetMapping("/workspace/statistical")
    public String statisticalWorkspace(Model model, Principal principal) {
        AnalyticalFilterRequest unfiltered = new AnalyticalFilterRequest();
        StatisticalSummaryResponse summary = statisticalAnalysisService.computeSummary(unfiltered);
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
        model.addAttribute("summary", summary);
        return "statistical";
    }

    @PostMapping("/api/statistical/summary")
    @ResponseBody
    public ApiResponse<StatisticalSummaryResponse> summary(
            @RequestBody(required = false) AnalyticalFilterRequest filter) {
        AnalyticalFilterRequest effectiveFilter = (filter != null) ? filter : new AnalyticalFilterRequest();
        return ApiResponse.success(statisticalAnalysisService.computeSummary(effectiveFilter));
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

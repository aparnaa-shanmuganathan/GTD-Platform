package com.gtd.platform.workspace.executive;

import com.gtd.platform.service.agg.KpiAggregationService;
import com.gtd.platform.service.agg.RankingAndBreakdownService;
import com.gtd.platform.service.insight.InsightService;
import com.gtd.platform.service.report.ReportGenerationService;
import com.gtd.platform.service.report.ReportSummary;
import com.gtd.platform.service.trend.TrendService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimRegionRepository;
import com.gtd.platform.shared.reporting.GeneratedReport.Format;
import java.security.Principal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ADC-EXEC-001: the Executive Workspace. {@code GET /workspace/executive}
 * server-renders the initial dashboard against the unfiltered dataset (real
 * data, computed via SVC-AGG/SVC-TREND/{@code RankingAndBreakdownService}/
 * INSIGHT - never mock/hardcoded); the filter bar then re-queries
 * {@code POST /api/executive/dashboard} client-side, matching CLAUDE.md
 * section 9's "all endpoints are POST" and the &le;2s filter-apply NFR
 * (measured, not assumed - see {@code ExecutiveWorkspaceIntegrationTest}).
 *
 * <p>{@code GET /workspace/executive} itself does not use the section 9
 * envelope (a server-rendered page route, not a data API endpoint - the
 * same reasoning {@code LoginController}/{@code HomeController} documented
 * in Phase 2); the two {@code POST /api/executive/*} endpoints do.
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - {@code principal} is {@code null} for an anonymous visitor.
 * {@link #generateReport} still works anonymously (it's an analytical
 * action, not an import), just with a {@code null} {@code adminUserId}
 * attribution on the generated report.
 */
@Controller
public class ExecutiveController {

    private static final DateTimeFormatter DEFAULT_NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final KpiAggregationService kpiAggregationService;
    private final TrendService trendService;
    private final RankingAndBreakdownService rankingAndBreakdownService;
    private final InsightService insightService;
    private final ReportGenerationService reportGenerationService;
    private final AdminUserRepository adminUserRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DimRegionRepository regionRepository;
    private final DimAttackTypeRepository attackTypeRepository;

    public ExecutiveController(KpiAggregationService kpiAggregationService, TrendService trendService,
            RankingAndBreakdownService rankingAndBreakdownService, InsightService insightService,
            ReportGenerationService reportGenerationService, AdminUserRepository adminUserRepository,
            JdbcTemplate jdbcTemplate, DimRegionRepository regionRepository,
            DimAttackTypeRepository attackTypeRepository) {
        this.kpiAggregationService = kpiAggregationService;
        this.trendService = trendService;
        this.rankingAndBreakdownService = rankingAndBreakdownService;
        this.insightService = insightService;
        this.reportGenerationService = reportGenerationService;
        this.adminUserRepository = adminUserRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.regionRepository = regionRepository;
        this.attackTypeRepository = attackTypeRepository;
    }

    @GetMapping("/workspace/executive")
    public String executiveWorkspace(Model model, Principal principal) {
        AnalyticalFilterRequest unfiltered = new AnalyticalFilterRequest();
        ExecutiveDashboardResponse dashboard = buildDashboard(unfiltered);
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
        // Passed as the object itself, not pre-serialized: Thymeleaf's
        // th:inline="javascript" (see executive.html) serializes a POJO/record
        // model attribute to a real JS object literal on its own. Serializing
        // it to a JSON *string* here first would make Thymeleaf's inlining
        // treat it as a String value and re-quote/escape the whole thing,
        // producing a JS string containing JSON text instead of a JS object.
        model.addAttribute("dashboard", dashboard);
        return "executive";
    }

    @PostMapping("/api/executive/dashboard")
    @ResponseBody
    public ApiResponse<ExecutiveDashboardResponse> dashboard(
            @RequestBody(required = false) AnalyticalFilterRequest filter) {
        AnalyticalFilterRequest effectiveFilter = (filter != null) ? filter : new AnalyticalFilterRequest();
        return ApiResponse.success(buildDashboard(effectiveFilter));
    }

    /**
     * Wired to the real REPORT service as of Phase 6 (was an ERR-REPORT-001
     * stub in Phase 4). Always generates a PDF - a single-click "give me a
     * PDF of what I'm looking at right now" action; a user who specifically
     * wants CSV or wants to name the report themselves uses the Report
     * Workspace's own generate flow instead, which offers both.
     */
    @PostMapping("/api/executive/report")
    @ResponseBody
    public ApiResponse<ReportSummary> generateReport(@RequestBody(required = false) AnalyticalFilterRequest filter,
            Principal principal) {
        Long adminUserId = (principal != null)
                ? adminUserRepository.findByUsername(principal.getName()).map(AdminUser::getId).orElse(null)
                : null;
        String reportName = "Executive Workspace Report — "
                + DEFAULT_NAME_TIMESTAMP.format(Instant.now().atZone(ZoneOffset.UTC));
        ReportSummary summary = reportGenerationService.generate(filter, Format.PDF, reportName, adminUserId);
        return ApiResponse.success(summary);
    }

    private ExecutiveDashboardResponse buildDashboard(AnalyticalFilterRequest filter) {
        return new ExecutiveDashboardResponse(
                kpiAggregationService.computeExecutiveKpis(filter),
                rankingAndBreakdownService.topCountriesByIncidentCount(filter),
                trendService.computeIncidentsByYearTrend(filter),
                rankingAndBreakdownService.attackTypeBreakdown(filter),
                rankingAndBreakdownService.weaponTypeBreakdown(filter),
                rankingAndBreakdownService.targetTypeBreakdown(filter),
                insightService.generateExecutiveInsights(filter)
        );
    }

    /**
     * Powers the filter bar's date-range presets (which anchor "last N
     * years" on the data's own max year, not wall-clock time - the real GTD
     * dataset may not extend to the present day). A single trivial query
     * for UI scaffolding, not a computation - kept inline rather than
     * routed through a service for it alone.
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

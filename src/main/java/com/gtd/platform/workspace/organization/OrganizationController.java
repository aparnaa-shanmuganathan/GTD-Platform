package com.gtd.platform.workspace.organization;

import com.gtd.platform.service.agg.KpiAggregationService;
import com.gtd.platform.service.agg.RankingAndBreakdownService;
import com.gtd.platform.service.trend.TrendService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.api.RankingResponse;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimOrganization;
import com.gtd.platform.shared.db.DimOrganizationRepository;
import com.gtd.platform.shared.db.DimRegionRepository;
import com.gtd.platform.shared.exception.ApiException;
import com.gtd.platform.shared.exception.ErrorCode;
import java.security.Principal;
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
 * ADC-ORGANIZATION-001: the Organization Workspace (Phase 8). {@code GET
 * /workspace/organization} server-renders the initial ranking against the
 * unfiltered dataset; the filter bar and organization drill-down are
 * client-driven {@code POST /api/organization/*} calls, matching CLAUDE.md
 * section 9's "all endpoints are POST".
 *
 * <p>"Dedicated unknown-organization handling" (AWS-UNK-001) has two
 * visible pieces: {@code RankingAndBreakdownService.topOrganizationsByIncidentCount}
 * excludes the seeded {@code is_unknown} row from the ranking, and {@link
 * KpiAggregationService#computeOrganizationKpis} appends an "Unattributed
 * incidents" KPI to the workspace's top KPI row. The drill-down instead
 * uses plain {@link KpiAggregationService#computeExecutiveKpis} (the same
 * SAV-001..006 shape {@code RegionDashboardEntry}/{@code CountryDashboardEntry}
 * use) - once scoped to a single named organization, "incidents attributed
 * to the unknown organization" is always zero and not worth showing.
 *
 * <p>No dedicated service, same reasoning as Region Workspace: scoping the
 * canonical filter to one organization and calling SVC-AGG/SVC-TREND is
 * orchestration, not new analysis.
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - {@code principal} is {@code null} for an anonymous visitor.
 */
@Controller
public class OrganizationController {

    private static final int ORGANIZATION_RANKING_LIMIT = 50;

    private final RankingAndBreakdownService rankingAndBreakdownService;
    private final KpiAggregationService kpiAggregationService;
    private final TrendService trendService;
    private final JdbcTemplate jdbcTemplate;
    private final DimRegionRepository regionRepository;
    private final DimAttackTypeRepository attackTypeRepository;
    private final DimOrganizationRepository organizationRepository;

    public OrganizationController(RankingAndBreakdownService rankingAndBreakdownService,
            KpiAggregationService kpiAggregationService, TrendService trendService, JdbcTemplate jdbcTemplate,
            DimRegionRepository regionRepository, DimAttackTypeRepository attackTypeRepository,
            DimOrganizationRepository organizationRepository) {
        this.rankingAndBreakdownService = rankingAndBreakdownService;
        this.kpiAggregationService = kpiAggregationService;
        this.trendService = trendService;
        this.jdbcTemplate = jdbcTemplate;
        this.regionRepository = regionRepository;
        this.attackTypeRepository = attackTypeRepository;
        this.organizationRepository = organizationRepository;
    }

    @GetMapping("/workspace/organization")
    public String organizationWorkspace(Model model, Principal principal) {
        AnalyticalFilterRequest unfiltered = new AnalyticalFilterRequest();
        RankingResponse ranking =
                rankingAndBreakdownService.topOrganizationsByIncidentCount(unfiltered, ORGANIZATION_RANKING_LIMIT);
        List<KpiResponse> kpis = kpiAggregationService.computeOrganizationKpis(unfiltered);
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
        model.addAttribute("ranking", ranking);
        model.addAttribute("kpis", kpis);
        return "organization";
    }

    @PostMapping("/api/organization/ranking")
    @ResponseBody
    public ApiResponse<RankingResponse> ranking(@RequestBody(required = false) AnalyticalFilterRequest filter) {
        AnalyticalFilterRequest effectiveFilter = (filter != null) ? filter : new AnalyticalFilterRequest();
        return ApiResponse.success(
                rankingAndBreakdownService.topOrganizationsByIncidentCount(effectiveFilter, ORGANIZATION_RANKING_LIMIT));
    }

    @PostMapping("/api/organization/kpis")
    @ResponseBody
    public ApiResponse<List<KpiResponse>> kpis(@RequestBody(required = false) AnalyticalFilterRequest filter) {
        AnalyticalFilterRequest effectiveFilter = (filter != null) ? filter : new AnalyticalFilterRequest();
        return ApiResponse.success(kpiAggregationService.computeOrganizationKpis(effectiveFilter));
    }

    @PostMapping("/api/organization/drilldown")
    @ResponseBody
    public ApiResponse<OrganizationDashboardEntry> drilldown(@RequestBody AnalyticalFilterRequest filter) {
        List<Long> organizationIds = filter.getOrganizationIds();
        if (organizationIds == null || organizationIds.size() != 1) {
            throw new ApiException(ErrorCode.ERR_ORGANIZATION_001);
        }
        long organizationId = organizationIds.get(0);
        DimOrganization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.ERR_ORGANIZATION_002, "No organization with id " + organizationId));

        AnalyticalFilterRequest scoped = scopedToOrganization(filter, organizationId);
        OrganizationDashboardEntry entry = new OrganizationDashboardEntry(
                organizationId,
                organization.getName(),
                kpiAggregationService.computeExecutiveKpis(scoped),
                trendService.computeIncidentsByYearTrend(scoped));
        return ApiResponse.success(entry);
    }

    /**
     * Copies every filter dimension except {@code organizationIds} from {@code base} (date
     * range, region, attack/weapon/target type all still apply as shared cross-cutting
     * filters), then pins {@code organizationIds} to exactly the one organization being
     * drilled into - the same {@code scopedToCountry}/{@code scopedToRegion} pattern.
     */
    private AnalyticalFilterRequest scopedToOrganization(AnalyticalFilterRequest base, long organizationId) {
        AnalyticalFilterRequest scoped = new AnalyticalFilterRequest();
        scoped.setStartDate(base.getStartDate());
        scoped.setEndDate(base.getEndDate());
        scoped.setRegionIds(base.getRegionIds());
        scoped.setAttackTypeIds(base.getAttackTypeIds());
        scoped.setWeaponTypeIds(base.getWeaponTypeIds());
        scoped.setTargetTypeIds(base.getTargetTypeIds());
        scoped.setOrganizationIds(List.of(organizationId));
        return scoped;
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

package com.gtd.platform.workspace.region;

import com.gtd.platform.service.agg.KpiAggregationService;
import com.gtd.platform.service.agg.RankingAndBreakdownService;
import com.gtd.platform.service.trend.TrendService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.api.RankingResponse;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimRegion;
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
 * ADC-REGION-001: the Region Workspace (Phase 8). {@code GET /workspace/region}
 * server-renders the initial ranking against the unfiltered dataset (real
 * data, via {@code RankingAndBreakdownService.topRegionsByIncidentCount} -
 * the same CAC-AN-002 component every other workspace reuses); the filter
 * bar and region drill-down are client-driven {@code POST /api/region/*}
 * calls, matching CLAUDE.md section 9's "all endpoints are POST".
 *
 * <p>No dedicated service exists for this workspace (unlike Country's
 * SVC-COMPARE): "regional rollups + member-country breakdown" needs no
 * computation SVC-AGG/SVC-TREND/{@code RankingAndBreakdownService} don't
 * already own - scoping the canonical filter to a single region and calling
 * those services is orchestration, not a new analytical capability, so it
 * lives directly in this controller (the same pattern {@code
 * ExecutiveController} already uses for its own dashboard composition).
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - {@code principal} is {@code null} for an anonymous visitor.
 */
@Controller
public class RegionController {

    /** Comfortably above the 12 canonical seeded regions (CLAUDE.md section 12, Phase 1 decisions). */
    private static final int REGION_RANKING_LIMIT = 20;

    /** Matches Country Workspace's own ranking cutoff (Phase 5) - generous without being unbounded. */
    private static final int MEMBER_COUNTRY_LIMIT = 50;

    private final RankingAndBreakdownService rankingAndBreakdownService;
    private final KpiAggregationService kpiAggregationService;
    private final TrendService trendService;
    private final JdbcTemplate jdbcTemplate;
    private final DimRegionRepository regionRepository;
    private final DimAttackTypeRepository attackTypeRepository;

    public RegionController(RankingAndBreakdownService rankingAndBreakdownService,
            KpiAggregationService kpiAggregationService, TrendService trendService, JdbcTemplate jdbcTemplate,
            DimRegionRepository regionRepository, DimAttackTypeRepository attackTypeRepository) {
        this.rankingAndBreakdownService = rankingAndBreakdownService;
        this.kpiAggregationService = kpiAggregationService;
        this.trendService = trendService;
        this.jdbcTemplate = jdbcTemplate;
        this.regionRepository = regionRepository;
        this.attackTypeRepository = attackTypeRepository;
    }

    @GetMapping("/workspace/region")
    public String regionWorkspace(Model model, Principal principal) {
        AnalyticalFilterRequest unfiltered = new AnalyticalFilterRequest();
        RankingResponse ranking = rankingAndBreakdownService.topRegionsByIncidentCount(unfiltered, REGION_RANKING_LIMIT);
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
        return "region";
    }

    @PostMapping("/api/region/ranking")
    @ResponseBody
    public ApiResponse<RankingResponse> ranking(@RequestBody(required = false) AnalyticalFilterRequest filter) {
        AnalyticalFilterRequest effectiveFilter = (filter != null) ? filter : new AnalyticalFilterRequest();
        return ApiResponse.success(
                rankingAndBreakdownService.topRegionsByIncidentCount(effectiveFilter, REGION_RANKING_LIMIT));
    }

    @PostMapping("/api/region/drilldown")
    @ResponseBody
    public ApiResponse<RegionDashboardEntry> drilldown(@RequestBody AnalyticalFilterRequest filter) {
        List<Long> regionIds = filter.getRegionIds();
        if (regionIds == null || regionIds.size() != 1) {
            throw new ApiException(ErrorCode.ERR_REGION_001);
        }
        long regionId = regionIds.get(0);
        DimRegion region = regionRepository.findById(regionId)
                .orElseThrow(() -> new ApiException(ErrorCode.ERR_REGION_002, "No region with id " + regionId));

        AnalyticalFilterRequest scoped = scopedToRegion(filter, regionId);
        RegionDashboardEntry entry = new RegionDashboardEntry(
                regionId,
                region.getName(),
                kpiAggregationService.computeExecutiveKpis(scoped),
                trendService.computeIncidentsByYearTrend(scoped),
                rankingAndBreakdownService.topCountriesByIncidentCount(scoped, MEMBER_COUNTRY_LIMIT));
        return ApiResponse.success(entry);
    }

    /**
     * Copies every filter dimension except {@code regionIds} from {@code base} (date range,
     * attack/weapon/target type, organization all still apply as shared cross-cutting filters),
     * then pins {@code regionIds} to exactly the one region being drilled into - the same
     * {@code scopedToCountry} pattern {@code CompareService} established for Country Workspace.
     */
    private AnalyticalFilterRequest scopedToRegion(AnalyticalFilterRequest base, long regionId) {
        AnalyticalFilterRequest scoped = new AnalyticalFilterRequest();
        scoped.setStartDate(base.getStartDate());
        scoped.setEndDate(base.getEndDate());
        scoped.setAttackTypeIds(base.getAttackTypeIds());
        scoped.setWeaponTypeIds(base.getWeaponTypeIds());
        scoped.setTargetTypeIds(base.getTargetTypeIds());
        scoped.setOrganizationIds(base.getOrganizationIds());
        scoped.setRegionIds(List.of(regionId));
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

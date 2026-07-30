package com.gtd.platform.workspace.country;

import com.gtd.platform.service.agg.RankingAndBreakdownService;
import com.gtd.platform.service.compare.CompareService;
import com.gtd.platform.service.compare.CountryDashboardEntry;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.api.RankingResponse;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimCountryRepository;
import com.gtd.platform.shared.db.DimRegionRepository;
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
 * ADC-COUNTRY-001: the Country Workspace. {@code GET /workspace/country}
 * server-renders the initial ranking view against the unfiltered dataset
 * (real data, via {@code RankingAndBreakdownService} - the exact CAC-AN-002
 * component Phase 4 built, reused unmodified beyond its Phase 5
 * {@code entityId} addition); the filter bar and the compare/drill-down
 * actions then re-query the three {@code POST /api/country/*} endpoints
 * client-side, matching CLAUDE.md section 9's "all endpoints are POST".
 *
 * <p>Per the confirmed navigation-state decision for this phase (CLAUDE.md
 * section 12): the ranking, compare, and drill-down views are three
 * client-side-only sections of this one page - no URL/history state, same
 * as how the Executive Workspace's filter bar already works. A browser
 * back/refresh always lands back on the default ranking view, never
 * mid-comparison or mid-drill-down.
 *
 * <p>{@code GET /workspace/country} itself does not use the section 9
 * envelope (a server-rendered page route, not a data API endpoint - the
 * same reasoning {@code ExecutiveController} documented in Phase 4); the
 * three {@code POST /api/country/*} endpoints do.
 */
@Controller
public class CountryController {

    /**
     * The Country Workspace's own ranking is the workspace's entire purpose, unlike the
     * Executive Workspace's compact top-10 widget - a generous cutoff, not "all countries",
     * since the seeded/real country set can run into the hundreds and an unbounded ranking
     * buys nothing a rank-50 cutoff doesn't already show.
     */
    private static final int COUNTRY_RANKING_LIMIT = 50;

    private final RankingAndBreakdownService rankingAndBreakdownService;
    private final CompareService compareService;
    private final JdbcTemplate jdbcTemplate;
    private final DimRegionRepository regionRepository;
    private final DimAttackTypeRepository attackTypeRepository;
    private final DimCountryRepository countryRepository;

    public CountryController(RankingAndBreakdownService rankingAndBreakdownService, CompareService compareService,
            JdbcTemplate jdbcTemplate, DimRegionRepository regionRepository,
            DimAttackTypeRepository attackTypeRepository, DimCountryRepository countryRepository) {
        this.rankingAndBreakdownService = rankingAndBreakdownService;
        this.compareService = compareService;
        this.jdbcTemplate = jdbcTemplate;
        this.regionRepository = regionRepository;
        this.attackTypeRepository = attackTypeRepository;
        this.countryRepository = countryRepository;
    }

    @GetMapping("/workspace/country")
    public String countryWorkspace(Model model, Principal principal) {
        AnalyticalFilterRequest unfiltered = new AnalyticalFilterRequest();
        RankingResponse ranking = rankingAndBreakdownService.topCountriesByIncidentCount(unfiltered, COUNTRY_RANKING_LIMIT);
        YearBounds yearBounds = queryYearBounds();

        model.addAttribute("username", principal.getName());
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
        model.addAttribute("countries", countryRepository.findAll().stream()
                .filter(c -> !c.isUnknown())
                .sorted(Comparator.comparing(c -> c.getName()))
                .toList());
        // Passed as the object itself, not pre-serialized - same reasoning as
        // ExecutiveController's "dashboard" model attribute (see its Javadoc):
        // th:inline="javascript" serializes a record model attribute to a real
        // JS object literal on its own.
        model.addAttribute("ranking", ranking);
        return "country";
    }

    @PostMapping("/api/country/ranking")
    @ResponseBody
    public ApiResponse<RankingResponse> ranking(@RequestBody(required = false) AnalyticalFilterRequest filter) {
        AnalyticalFilterRequest effectiveFilter = (filter != null) ? filter : new AnalyticalFilterRequest();
        return ApiResponse.success(rankingAndBreakdownService.topCountriesByIncidentCount(effectiveFilter, COUNTRY_RANKING_LIMIT));
    }

    @PostMapping("/api/country/compare")
    @ResponseBody
    public ApiResponse<List<CountryDashboardEntry>> compare(@RequestBody AnalyticalFilterRequest filter) {
        return ApiResponse.success(compareService.compare(filter));
    }

    @PostMapping("/api/country/drilldown")
    @ResponseBody
    public ApiResponse<CountryDashboardEntry> drilldown(@RequestBody AnalyticalFilterRequest filter) {
        return ApiResponse.success(compareService.drilldown(filter));
    }

    /**
     * Powers the filter bar's date-range presets, anchored on the data's own
     * max year rather than wall-clock time - same reasoning and same
     * one-off-query-not-a-service treatment as {@code ExecutiveController}'s
     * identical helper (Phase 4).
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

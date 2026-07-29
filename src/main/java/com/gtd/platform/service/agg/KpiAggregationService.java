package com.gtd.platform.service.agg;

import com.gtd.platform.service.trend.TrendService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.db.DimOrganizationRepository;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder.BuiltFilter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * SVC-AGG: the Executive Workspace's global KPIs, SAV-001 through SAV-006
 * (CLAUDE.md section 6), respecting the canonical filter DTO (CAC-UI-003).
 *
 * <p>Queries {@code fact_incident} directly, not the six {@code mv_*_year_agg}
 * materialized views: each view is grouped by exactly one non-year
 * dimension, which cannot support the canonical filter's arbitrary
 * simultaneous combination of dimension filters (region AND attack type AND
 * organization, etc. all at once). At the platform's target scale (up to
 * ~250k rows, CLAUDE.md section 10) a direct query against the indexed FK
 * columns built in Phase 1 comfortably meets the NFR targets - measured, not
 * assumed, by {@code ExecutiveWorkspaceIntegrationTest}. See CLAUDE.md
 * section 12 for this decision recorded in full. {@link com.gtd.platform.service.agg.RankingAndBreakdownService}
 * makes the opposite call for the ranking/breakdown widgets, where the
 * materialized views are an exact structural fit.
 *
 * <p>SAV-005 (Countries Affected) needs no AWS-UNK-001 exclusion: Phase 3's
 * import pipeline rejects rows with an unresolvable country outright
 * (staging-level exclusion, CLAUDE.md section 8), so every {@code country_id}
 * in {@code fact_incident} is already a genuine, named country. SAV-006
 * (Active Organizations) does need it - the seeded {@code is_unknown}
 * {@code dim_organization} row is excluded from the distinct count via a
 * {@code CASE WHEN} in the query below, the first place this rule is
 * actually enforced (documented but unimplemented since Phase 1).
 */
@Service
public class KpiAggregationService {

    private final JdbcTemplate jdbcTemplate;
    private final TrendService trendService;
    private final DimOrganizationRepository organizationRepository;

    public KpiAggregationService(JdbcTemplate jdbcTemplate, TrendService trendService,
            DimOrganizationRepository organizationRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.trendService = trendService;
        this.organizationRepository = organizationRepository;
    }

    public List<KpiResponse> computeExecutiveKpis(AnalyticalFilterRequest filter) {
        long unknownOrgId = organizationRepository.findByUnknownTrue()
                .orElseThrow(() -> new IllegalStateException("dim_organization has no seeded is_unknown row"))
                .getId();

        Totals totals = queryTotals(filter, unknownOrgId);

        BigDecimal successRate = totals.incidentCount() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(totals.successCount())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totals.incidentCount()), 2, RoundingMode.HALF_UP);

        KpiResponse.Trend incidentsTrend = growthRateTrend(trendService.incidentCountByYear(filter));
        KpiResponse.Trend fatalitiesTrend = growthRateTrend(trendService.fatalitiesByYear(filter));
        KpiResponse.Trend injuriesTrend = growthRateTrend(trendService.injuriesByYear(filter));
        KpiResponse.Trend successRateTrend = growthRateTrend(trendService.successRatePercentByYear(filter));

        return List.of(
                new KpiResponse("kpiIncidents", "Total incidents", "SAV-001",
                        BigDecimal.valueOf(totals.incidentCount()), null, incidentsTrend, null),
                new KpiResponse("kpiFatalities", "Total fatalities", "SAV-002",
                        orZero(totals.fatalityCount()), null, fatalitiesTrend, null),
                new KpiResponse("kpiInjuries", "Total injuries", "SAV-003",
                        orZero(totals.injuryCount()), null, injuriesTrend, null),
                new KpiResponse("kpiSuccess", "Success rate", "SAV-004",
                        successRate, "%", successRateTrend, null),
                new KpiResponse("kpiCountries", "Countries affected", "SAV-005",
                        BigDecimal.valueOf(totals.countriesAffected()), null, null, null),
                new KpiResponse("kpiOrgs", "Active organizations", "SAV-006",
                        BigDecimal.valueOf(totals.activeOrganizations()), null, null, null)
        );
    }

    private Totals queryTotals(AnalyticalFilterRequest filter, long unknownOrgId) {
        BuiltFilter built = FactIncidentFilterSqlBuilder.build(filter);
        String sql = "SELECT "
                + "COUNT(*) AS incident_count, "
                + "SUM(nkill) AS fatality_count, "
                + "SUM(nwound) AS injury_count, "
                + "COUNT(*) FILTER (WHERE is_success) AS success_count, "
                + "COUNT(DISTINCT country_id) AS countries_affected, "
                + "COUNT(DISTINCT CASE WHEN organization_id <> ? THEN organization_id END) AS active_organizations "
                + "FROM fact_incident WHERE " + built.whereClauseSql();

        List<Object> params = new ArrayList<>();
        params.add(unknownOrgId);
        params.addAll(built.params());

        return jdbcTemplate.queryForObject(sql, params.toArray(), (rs, rowNum) -> new Totals(
                rs.getLong("incident_count"),
                rs.getBigDecimal("fatality_count"),
                rs.getBigDecimal("injury_count"),
                rs.getLong("success_count"),
                rs.getLong("countries_affected"),
                rs.getLong("active_organizations")
        ));
    }

    private KpiResponse.Trend growthRateTrend(SortedMap<Integer, BigDecimal> yearlySeries) {
        return trendService.computeGrowthRate(yearlySeries)
                .map(result -> new KpiResponse.Trend(
                        directionOf(result.growthRatePercent()),
                        formatDelta(result.growthRatePercent()),
                        result.growthRatePercent()))
                .orElse(null);
    }

    private KpiResponse.Direction directionOf(BigDecimal growthRatePercent) {
        int cmp = growthRatePercent.compareTo(BigDecimal.ZERO);
        if (cmp > 0) {
            return KpiResponse.Direction.UP;
        }
        if (cmp < 0) {
            return KpiResponse.Direction.DOWN;
        }
        return KpiResponse.Direction.FLAT;
    }

    private String formatDelta(BigDecimal growthRatePercent) {
        BigDecimal rounded = growthRatePercent.setScale(1, RoundingMode.HALF_UP);
        String sign = rounded.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        return sign + rounded + "% vs trailing 5yr avg";
    }

    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record Totals(
            long incidentCount,
            BigDecimal fatalityCount,
            BigDecimal injuryCount,
            long successCount,
            long countriesAffected,
            long activeOrganizations
    ) {
    }
}

package com.gtd.platform.service.casualty;

import com.gtd.platform.service.casualty.SeverityBandClassifier.SeverityBand;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder.BuiltFilter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Casualty Workspace's genuinely new computations (Phase 8): Total Casualties and Severity
 * Classification, two SAV items (CLAUDE.md section 6) unclaimed by any other workspace and not
 * in SVC-STAT's exclusive list (Trend Slope/Moving Average/Percentile Ranking/Correlation/
 * Outlier Detection). "Statistical summary" deliberately stays to mean/total/band-distribution
 * here - no median/percentiles (percentile ranking is SVC-STAT-exclusive) and no {@code
 * CAC-AN-006} Statistical Summary component (CLAUDE.md section 12's Phase 2 decisions reserve
 * that component for whichever phase builds the Statistical Workspace, i.e. Phase 9) - this
 * class's outputs use the same {@code KpiResponse}/{@code ChartResponse} shapes (CAC-AN-001/003)
 * every other workspace already does.
 *
 * <p>Reused, not reimplemented: {@code KpiAggregationService.computeExecutiveKpis} already
 * provides SAV-002/SAV-003 (Total Fatalities/Injuries) individually - {@link
 * #computeCasualtyKpis} only adds what those don't already cover (the fatalities+injuries
 * *combined* total, and the two *per-incident average* figures).
 */
@Service
public class CasualtyAggregationService {

    private static final List<SeverityBand> BAND_DISPLAY_ORDER =
            List.of(SeverityBand.LOW, SeverityBand.MODERATE, SeverityBand.HIGH, SeverityBand.CATASTROPHIC, SeverityBand.UNKNOWN);

    private final JdbcTemplate jdbcTemplate;

    public CasualtyAggregationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * SAV "Total Casualties" (combined fatalities + injuries) plus the two per-incident
     * averages, against the full canonical filter.
     *
     * <p>{@code COALESCE(SUM(nkill),0) + COALESCE(SUM(nwound),0)}: this is <strong>not</strong>
     * the per-row "coalesce unknown to zero" CLAUDE.md sections 7/8 forbid - each {@code SUM()}
     * already correctly skips its own {@code NULL} rows (the same convention the {@code
     * mv_*_year_agg} views' {@code fatality_count}/{@code injury_count} already established).
     * The outer {@code COALESCE} only prevents one *wholly-null* column from nullifying the
     * other, already-known column when the two are added together (a country where every
     * incident has a known {@code nkill} but entirely unrecorded {@code nwound} would otherwise
     * see its known fatality total silently erased by {@code 500 + NULL = NULL} in SQL). It
     * mirrors the same display-layer convention {@code KpiAggregationService.computeExecutiveKpis}
     * already applies via its own {@code orZero} helper for {@code kpiFatalities}/{@code
     * kpiInjuries} - the underlying query never coalesces, but a wholly-unknown aggregate
     * displays as {@code 0} rather than a broken/absent KPI card.
     */
    public List<KpiResponse> computeCasualtyKpis(AnalyticalFilterRequest filter) {
        BuiltFilter built = FactIncidentFilterSqlBuilder.build(filter);
        String sql = "SELECT COALESCE(SUM(nkill),0) + COALESCE(SUM(nwound),0) AS total_casualties, "
                + "AVG(nkill) AS avg_fatalities, AVG(nwound) AS avg_injuries "
                + "FROM fact_incident WHERE " + built.whereClauseSql();

        CasualtyTotals totals = jdbcTemplate.queryForObject(sql, built.params().toArray(), (rs, rowNum) ->
                new CasualtyTotals(rs.getBigDecimal("total_casualties"), rs.getBigDecimal("avg_fatalities"), rs.getBigDecimal("avg_injuries")));

        return List.of(
                new KpiResponse("kpiTotalCasualties", "Total casualties", "SAV-012",
                        orZero(totals.totalCasualties()), null, null, null),
                new KpiResponse("kpiAvgFatalities", "Avg fatalities / incident", "SAV-007",
                        orZero(totals.avgFatalities()).setScale(2, RoundingMode.HALF_UP), null, null, null),
                new KpiResponse("kpiAvgInjuries", "Avg injuries / incident", "SAV-007",
                        orZero(totals.avgInjuries()).setScale(2, RoundingMode.HALF_UP), null, null, null)
        );
    }

    /**
     * SAV "Severity Classification" (CLAUDE.md section 6): incident counts per {@link
     * SeverityBand}, against the full canonical filter. Computed as one {@code GROUP BY} query
     * (not by pulling every matching row into Java and classifying one at a time via {@link
     * SeverityBandClassifier#classify} - infeasible at this platform's scale, CLAUDE.md section
     * 10) using a SQL {@code CASE WHEN} built from {@link SeverityBandClassifier}'s threshold
     * constants - see that class's Javadoc for why the constants stay single-sourced even
     * though the classification rule is necessarily expressed twice.
     */
    public ChartResponse computeSeverityBandDistribution(AnalyticalFilterRequest filter) {
        BuiltFilter built = FactIncidentFilterSqlBuilder.build(filter);
        String caseExpr = "CASE "
                + "WHEN nkill IS NULL AND nwound IS NULL THEN 'UNKNOWN' "
                + "WHEN COALESCE(nkill,0) + COALESCE(nwound,0) <= " + SeverityBandClassifier.LOW_MAX + " THEN 'LOW' "
                + "WHEN COALESCE(nkill,0) + COALESCE(nwound,0) <= " + SeverityBandClassifier.MODERATE_MAX + " THEN 'MODERATE' "
                + "WHEN COALESCE(nkill,0) + COALESCE(nwound,0) <= " + SeverityBandClassifier.HIGH_MAX + " THEN 'HIGH' "
                + "ELSE 'CATASTROPHIC' END";
        String sql = "SELECT " + caseExpr + " AS band, COUNT(*) AS total "
                + "FROM fact_incident WHERE " + built.whereClauseSql() + " "
                + "GROUP BY band";

        Map<SeverityBand, Long> countsByBand = new EnumMap<>(SeverityBand.class);
        jdbcTemplate.query(sql, built.params().toArray(), rs -> {
            countsByBand.put(SeverityBand.valueOf(rs.getString("band")), rs.getLong("total"));
        });

        List<String> categories = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        for (SeverityBand band : BAND_DISPLAY_ORDER) {
            categories.add(band.name());
            values.add(BigDecimal.valueOf(countsByBand.getOrDefault(band, 0L)));
        }

        return new ChartResponse("severityBandChart", "Incidents by severity band",
                ChartResponse.ChartType.BAR, categories, List.of(new ChartResponse.ChartSeries("Incidents", values)));
    }

    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record CasualtyTotals(BigDecimal totalCasualties, BigDecimal avgFatalities, BigDecimal avgInjuries) {
    }
}

package com.gtd.platform.service.trend;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder.BuiltFilter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * SVC-TREND: incidents-by-year trend series (CAC-AN-003) and the yearly
 * per-metric series that {@link GrowthRateCalculator} (SAV-008) is computed
 * from. Explicitly does <strong>not</strong> compute Trend Slope, Moving
 * Average, Percentile Ranking, Correlation, or Outlier Detection - those
 * belong exclusively to SVC-STAT (CLAUDE.md section 6); this only ever does
 * the simpler Growth Rate deviation Insights panels elsewhere use.
 *
 * <p>Queries {@code fact_incident} directly (not the materialized views) via
 * {@link FactIncidentFilterSqlBuilder} - the same data-source decision as
 * SVC-AGG, and for the same reason: the materialized views are each grouped
 * by a single non-year dimension, which can't support the canonical filter
 * DTO's arbitrary combination of dimension filters. See CLAUDE.md section 12.
 */
@Service
public class TrendService {

    private final JdbcTemplate jdbcTemplate;

    public TrendService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** CAC-AN-003 shape for the Executive Workspace's "Incidents by year" chart. */
    public ChartResponse computeIncidentsByYearTrend(AnalyticalFilterRequest filter) {
        SortedMap<Integer, BigDecimal> series = incidentCountByYear(filter);
        List<String> categories = series.keySet().stream().map(String::valueOf).toList();
        List<BigDecimal> data = new ArrayList<>(series.values());
        return new ChartResponse("trendChart", "Incidents by year", ChartResponse.ChartType.LINE,
                categories, List.of(new ChartResponse.ChartSeries("Incidents", data)));
    }

    /**
     * CAC-AN-003 shape for Attack Workspace's "success/failure trend" chart (Phase 8) - the
     * same {@link #successRatePercentByYear} figures Executive's KPI row already derives a
     * growth-rate delta from, just wrapped as a chart instead: 100% minus this series is the
     * failure rate, so no separate failure-rate computation is needed.
     */
    public ChartResponse computeSuccessRateByYearTrend(AnalyticalFilterRequest filter) {
        SortedMap<Integer, BigDecimal> series = successRatePercentByYear(filter);
        List<String> categories = series.keySet().stream().map(String::valueOf).toList();
        List<BigDecimal> data = new ArrayList<>(series.values());
        return new ChartResponse("successRateChart", "Success rate by year", ChartResponse.ChartType.LINE,
                categories, List.of(new ChartResponse.ChartSeries("Success rate", data)));
    }

    /** SAV-001 (Incident), one row per year. Reused for the trend chart and the "Total incidents" KPI's growth-rate delta. */
    public SortedMap<Integer, BigDecimal> incidentCountByYear(AnalyticalFilterRequest filter) {
        return queryYearlySeries(filter, "COUNT(*)");
    }

    /** SAV-002 (Fatalities), one row per year. {@code SUM()} skips NULL {@code nkill} rather than coalescing to zero (CLAUDE.md sections 7/8). */
    public SortedMap<Integer, BigDecimal> fatalitiesByYear(AnalyticalFilterRequest filter) {
        return queryYearlySeries(filter, "SUM(nkill)");
    }

    /** SAV-003 (Injuries), one row per year. Same null handling as {@link #fatalitiesByYear}. */
    public SortedMap<Integer, BigDecimal> injuriesByYear(AnalyticalFilterRequest filter) {
        return queryYearlySeries(filter, "SUM(nwound)");
    }

    /** SAV-004 (Success Rate) as a percentage, one row per year. */
    public SortedMap<Integer, BigDecimal> successRatePercentByYear(AnalyticalFilterRequest filter) {
        return queryYearlySeries(filter,
                "100.0 * COUNT(*) FILTER (WHERE is_success) / NULLIF(COUNT(*), 0)");
    }

    public Optional<GrowthRateCalculator.GrowthRateResult> computeGrowthRate(SortedMap<Integer, BigDecimal> series) {
        return GrowthRateCalculator.compute(series);
    }

    /**
     * Attack Workspace's "prevalence over time" chart (Phase 8): one line per non-unknown
     * attack type (AWS-UNK-001), against the full canonical filter - genuinely new (every
     * other method here produces exactly one series for the whole filtered set), but the same
     * yearly-series-query shape this class already owns, so it belongs here rather than a new
     * package. "Success/failure trend" needs no equivalent new method: {@link
     * #successRatePercentByYear} already answers it directly (100% minus success% is failure%).
     */
    public ChartResponse attackTypeIncidentsByYear(AnalyticalFilterRequest filter) {
        BuiltFilter built = FactIncidentFilterSqlBuilder.build(filter);
        String sql = "SELECT f.event_year AS year, d.name AS label, COUNT(*) AS total "
                + "FROM (SELECT event_year, attack_type_id FROM fact_incident WHERE " + built.whereClauseSql() + ") f "
                + "JOIN dim_attack_type d ON d.id = f.attack_type_id AND d.is_unknown = FALSE "
                + "GROUP BY f.event_year, d.name "
                + "ORDER BY d.name, f.event_year";

        Map<String, SortedMap<Integer, BigDecimal>> seriesByLabel = new LinkedHashMap<>();
        TreeSet<Integer> allYears = new TreeSet<>();
        jdbcTemplate.query(sql, built.params().toArray(), rs -> {
            String label = rs.getString("label");
            int year = rs.getInt("year");
            BigDecimal total = rs.getBigDecimal("total");
            seriesByLabel.computeIfAbsent(label, k -> new TreeMap<>()).put(year, total);
            allYears.add(year);
        });

        List<String> categories = allYears.stream().map(String::valueOf).toList();
        List<ChartResponse.ChartSeries> series = seriesByLabel.entrySet().stream()
                .map(entry -> new ChartResponse.ChartSeries(entry.getKey(),
                        allYears.stream().map(year -> entry.getValue().get(year)).toList()))
                .toList();

        return new ChartResponse("attackPrevalenceChart", "Attack type prevalence by year",
                ChartResponse.ChartType.LINE, categories, series);
    }

    private SortedMap<Integer, BigDecimal> queryYearlySeries(AnalyticalFilterRequest filter, String aggregateExpression) {
        BuiltFilter built = FactIncidentFilterSqlBuilder.build(filter);
        String sql = "SELECT event_year, " + aggregateExpression + " AS agg_value FROM fact_incident "
                + "WHERE " + built.whereClauseSql() + " GROUP BY event_year ORDER BY event_year";

        SortedMap<Integer, BigDecimal> series = new TreeMap<>();
        jdbcTemplate.query(sql, built.params().toArray(), rs -> {
            BigDecimal value = rs.getBigDecimal("agg_value");
            if (value != null) {
                series.put(rs.getInt("event_year"), value.setScale(6, RoundingMode.HALF_UP));
            }
        });
        return series;
    }
}

package com.gtd.platform.service.agg;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.RankingResponse;
import com.gtd.platform.shared.api.RankingResponse.RankingEntry;
import com.gtd.platform.shared.db.YearRangeSqlHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The Executive Workspace's top-countries ranking (CAC-AN-002) and
 * attack/weapon/target-type breakdown charts (CAC-AN-003) - all sourced
 * from the {@code mv_*_year_agg} materialized views (CLAUDE.md section 7),
 * an exact structural fit unlike {@link KpiAggregationService}'s KPIs (see
 * that class's Javadoc for why the two make opposite data-source choices).
 *
 * <p>Only the date-range half of the canonical filter DTO applies here: each
 * view is already grouped by exactly one non-year dimension (its own), so
 * "top countries filtered by attack type" or similar cross-dimension
 * filtering isn't something a single materialized view can answer - see
 * CLAUDE.md section 12.
 *
 * <p>Attack/weapon/target breakdowns exclude their dimension's seeded
 * {@code is_unknown} row (AWS-UNK-001, CLAUDE.md section 8). The country
 * ranking does not: per Phase 3, {@code fact_incident.country_id} is never
 * the product of an unknown-value fallback (staging-level exclusion means
 * unresolvable countries are rejected at import, not normalized to
 * "Unknown"), so there is nothing to exclude.
 */
@Service
public class RankingAndBreakdownService {

    private static final int TOP_COUNTRIES = 10;

    private final JdbcTemplate jdbcTemplate;

    public RankingAndBreakdownService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RankingResponse topCountriesByIncidentCount(AnalyticalFilterRequest filter) {
        List<CategoryCount> all = queryBreakdown(filter, "mv_country_year_agg", "country_id", "dim_country", false);
        BigDecimal grandTotal = all.stream().map(CategoryCount::value).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<RankingEntry> entries = new ArrayList<>();
        int rank = 1;
        for (CategoryCount category : all.subList(0, Math.min(TOP_COUNTRIES, all.size()))) {
            BigDecimal share = grandTotal.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : category.value().divide(grandTotal, 4, RoundingMode.HALF_UP);
            entries.add(new RankingEntry(rank++, category.label(), category.value(), share));
        }
        return new RankingResponse("countryRanking", "Top countries by incident count", entries);
    }

    public ChartResponse attackTypeBreakdown(AnalyticalFilterRequest filter) {
        return breakdownChart(filter, "attackChart", "Attack type", "mv_attacktype_year_agg", "attack_type_id", "dim_attack_type");
    }

    public ChartResponse weaponTypeBreakdown(AnalyticalFilterRequest filter) {
        return breakdownChart(filter, "weaponChart", "Weapon type", "mv_weapontype_year_agg", "weapon_type_id", "dim_weapon_type");
    }

    public ChartResponse targetTypeBreakdown(AnalyticalFilterRequest filter) {
        return breakdownChart(filter, "targetChart", "Target type", "mv_targettype_year_agg", "target_type_id", "dim_target_type");
    }

    private ChartResponse breakdownChart(AnalyticalFilterRequest filter, String id, String title,
            String viewName, String dimensionIdColumn, String dimensionTable) {
        List<CategoryCount> categories = queryBreakdown(filter, viewName, dimensionIdColumn, dimensionTable, true);
        List<String> labels = categories.stream().map(CategoryCount::label).toList();
        List<BigDecimal> values = categories.stream().map(CategoryCount::value).toList();
        return new ChartResponse(id, title, ChartResponse.ChartType.PIE, labels,
                List.of(new ChartResponse.ChartSeries("Incidents", values)));
    }

    private List<CategoryCount> queryBreakdown(AnalyticalFilterRequest filter, String viewName,
            String dimensionIdColumn, String dimensionTable, boolean excludeUnknown) {
        List<Object> params = new ArrayList<>();
        String yearWhere = YearRangeSqlHelper.whereClause(filter, params, "mv.event_year");
        String unknownClause = excludeUnknown ? " AND d.is_unknown = FALSE" : "";

        String sql = "SELECT d.name AS label, SUM(mv.incident_count) AS total "
                + "FROM " + viewName + " mv "
                + "JOIN " + dimensionTable + " d ON d.id = mv." + dimensionIdColumn + " "
                + "WHERE " + yearWhere + unknownClause + " "
                + "GROUP BY d.id, d.name "
                + "ORDER BY total DESC";

        return jdbcTemplate.query(sql, params.toArray(), (rs, rowNum) ->
                new CategoryCount(rs.getString("label"), rs.getBigDecimal("total")));
    }

    private record CategoryCount(String label, BigDecimal value) {
    }
}

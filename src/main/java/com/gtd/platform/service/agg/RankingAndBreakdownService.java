package com.gtd.platform.service.agg;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.RankingResponse;
import com.gtd.platform.shared.api.RankingResponse.RankingEntry;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder.BuiltFilter;
import com.gtd.platform.shared.db.YearRangeSqlHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The Executive/Country/Region Workspaces' entity rankings (CAC-AN-002) and
 * the Executive Workspace's attack/weapon/target-type breakdown charts
 * (CAC-AN-003).
 *
 * <p>Every ranking (country, region, and - Phase 8 - organization/attack/
 * weapon/target type) queries {@code fact_incident} directly via
 * {@link #queryDirectRanking}, the same full-filter approach
 * {@link KpiAggregationService}/{@code TrendService} use: every workspace's
 * filter bar sends region/attack-type/etc. filters alongside date range, and
 * a ranking that silently dropped everything but date range would show
 * incident counts inconsistent with the rest of the filtered page. The three
 * Executive-widget breakdown charts still source from the {@code
 * mv_*_year_agg} materialized views (CLAUDE.md section 7) with
 * date-range-only filtering (each view is grouped by exactly one non-year
 * dimension, so it cannot answer an arbitrary simultaneous combination of
 * dimension filters the way a direct {@code fact_incident} query can) - a
 * known, narrower limitation than the rankings have, left as-is pending a
 * similar fix if those compact widgets are found to need it too.
 *
 * <p>AWS-UNK-001 (CLAUDE.md section 8) excludes the seeded {@code is_unknown}
 * row from named-entity aggregates for Organization/Attack/Weapon/Target -
 * {@link #queryDirectRanking}'s {@code excludeUnknown} parameter controls
 * this per ranking. Country and Region rankings never exclude it: per Phase
 * 3, {@code fact_incident.country_id} is never the product of an
 * unknown-value fallback (staging-level exclusion means unresolvable
 * countries are rejected at import, not normalized to "Unknown"), and
 * regions are a closed, well-known set with no is_unknown fallback path
 * either.
 */
@Service
public class RankingAndBreakdownService {

    /** The Executive Workspace's compact widget default (Phase 4) - kept as the no-arg overload's behavior unchanged. */
    private static final int TOP_COUNTRIES = 10;

    private final JdbcTemplate jdbcTemplate;

    public RankingAndBreakdownService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Executive Workspace's compact top-10 widget (Phase 4) - see {@link #topCountriesByIncidentCount(AnalyticalFilterRequest, int)} for a caller-chosen limit. */
    public RankingResponse topCountriesByIncidentCount(AnalyticalFilterRequest filter) {
        return topCountriesByIncidentCount(filter, TOP_COUNTRIES);
    }

    /**
     * Same CAC-AN-002 ranking, with a caller-chosen cutoff - added in Phase 5 so the
     * Country Workspace's dedicated ranking view can show a fuller list than the
     * Executive Workspace's compact 10-row widget, without changing that widget's
     * existing behavior.
     */
    public RankingResponse topCountriesByIncidentCount(AnalyticalFilterRequest filter, int limit) {
        List<CategoryCount> all = queryDirectRanking(filter, "country_id", "dim_country", false);
        return toRankingResponse("countryRanking", "Top countries by incident count", all, limit);
    }

    /** Region Workspace's ranking (Phase 8) - regions ranked by incident count against the full canonical filter. */
    public RankingResponse topRegionsByIncidentCount(AnalyticalFilterRequest filter, int limit) {
        List<CategoryCount> all = queryDirectRanking(filter, "region_id", "dim_region", false);
        return toRankingResponse("regionRanking", "Top regions by incident count", all, limit);
    }

    /** Organization Workspace's ranking (Phase 8) - AWS-UNK-001 excludes the seeded is_unknown organization row. */
    public RankingResponse topOrganizationsByIncidentCount(AnalyticalFilterRequest filter, int limit) {
        List<CategoryCount> all = queryDirectRanking(filter, "organization_id", "dim_organization", true);
        return toRankingResponse("organizationRanking", "Top organizations by incident count", all, limit);
    }

    /** Attack Workspace's ranking (Phase 8) - AWS-UNK-001 excludes the seeded is_unknown attack type row. */
    public RankingResponse attackTypeRanking(AnalyticalFilterRequest filter, int limit) {
        List<CategoryCount> all = queryDirectRanking(filter, "attack_type_id", "dim_attack_type", true);
        return toRankingResponse("attackTypeRanking", "Attack types by incident count", all, limit);
    }

    /** Weapon Workspace's ranking (Phase 8) - AWS-UNK-001 excludes the seeded is_unknown weapon type row. */
    public RankingResponse weaponTypeRanking(AnalyticalFilterRequest filter, int limit) {
        List<CategoryCount> all = queryDirectRanking(filter, "weapon_type_id", "dim_weapon_type", true);
        return toRankingResponse("weaponTypeRanking", "Weapon types by incident count", all, limit);
    }

    /** Target Workspace's ranking (Phase 8) - AWS-UNK-001 excludes the seeded is_unknown target type row. */
    public RankingResponse targetTypeRanking(AnalyticalFilterRequest filter, int limit) {
        List<CategoryCount> all = queryDirectRanking(filter, "target_type_id", "dim_target_type", true);
        return toRankingResponse("targetTypeRanking", "Target types by incident count", all, limit);
    }

    private RankingResponse toRankingResponse(String id, String title, List<CategoryCount> all, int limit) {
        BigDecimal grandTotal = all.stream().map(CategoryCount::value).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<RankingEntry> entries = new ArrayList<>();
        int rank = 1;
        for (CategoryCount category : all.subList(0, Math.min(limit, all.size()))) {
            BigDecimal share = grandTotal.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : category.value().divide(grandTotal, 4, RoundingMode.HALF_UP);
            entries.add(new RankingEntry(rank++, category.label(), category.value(), share, category.entityId()));
        }
        return new RankingResponse(id, title, entries);
    }

    /**
     * Ranks every row of {@code dimensionTable} by incident count against the full canonical
     * filter (date range, region, country, attack/weapon/target type, organization - CAC-UI-003),
     * by querying {@code fact_incident} directly rather than any {@code mv_*_year_agg} view - see
     * this class's Javadoc for why a ranking needs the full filter set a single-dimension
     * materialized view can't provide.
     *
     * <p>The WHERE clause's unqualified column names (e.g. {@code "region_id"}) are only
     * unambiguous against a single-table {@code fact_incident} scope - {@code dim_country} also
     * has its own {@code region_id} FK column, so applying the filter inside this subquery
     * (before the join adds the dimension table's columns into scope) avoids an
     * ambiguous-column-reference error the same way {@code KpiAggregationService}/{@code
     * TrendService}'s direct (join-free) {@code fact_incident} queries never hit.
     */
    private List<CategoryCount> queryDirectRanking(AnalyticalFilterRequest filter, String fkColumn,
            String dimensionTable, boolean excludeUnknown) {
        BuiltFilter built = FactIncidentFilterSqlBuilder.build(filter);
        String unknownClause = excludeUnknown ? " AND d.is_unknown = FALSE" : "";
        String sql = "SELECT f." + fkColumn + " AS entity_id, d.name AS label, COUNT(*) AS total "
                + "FROM (SELECT " + fkColumn + " FROM fact_incident WHERE " + built.whereClauseSql() + ") f "
                + "JOIN " + dimensionTable + " d ON d.id = f." + fkColumn + unknownClause + " "
                + "GROUP BY f." + fkColumn + ", d.name "
                + "ORDER BY total DESC";

        return jdbcTemplate.query(sql, built.params().toArray(), (rs, rowNum) ->
                new CategoryCount(rs.getLong("entity_id"), rs.getString("label"), rs.getBigDecimal("total")));
    }

    /**
     * Weapon Workspace's "linkage to casualty severity" chart (Phase 8): average
     * fatalities/injuries per incident, one bar-chart category per non-unknown weapon type
     * (AWS-UNK-001), against the full canonical filter. Genuinely new - every other
     * breakdown/ranking in this class {@code COUNT}s incidents; this is the first to average
     * casualty columns instead, so it doesn't fit {@link #queryDirectRanking}'s shape.
     *
     * <p>{@code AVG(f.nkill)}/{@code AVG(f.nwound)} skip their own {@code NULL} rows per
     * standard SQL (never coalesced to zero, CLAUDE.md sections 7/8) - a weapon type where
     * every matching incident has unknown fatalities correctly yields a {@code NULL} average
     * rather than a misleading zero, the same convention the {@code mv_*_year_agg} views'
     * {@code fatality_count}/{@code injury_count} already established.
     */
    public ChartResponse weaponCasualtySeverityBreakdown(AnalyticalFilterRequest filter) {
        BuiltFilter built = FactIncidentFilterSqlBuilder.build(filter);
        String sql = "SELECT d.name AS label, "
                + "ROUND(AVG(f.nkill), 2) AS avg_fatalities, ROUND(AVG(f.nwound), 2) AS avg_injuries "
                + "FROM (SELECT weapon_type_id, nkill, nwound FROM fact_incident WHERE " + built.whereClauseSql() + ") f "
                + "JOIN dim_weapon_type d ON d.id = f.weapon_type_id AND d.is_unknown = FALSE "
                + "GROUP BY d.name "
                + "ORDER BY d.name";

        List<WeaponSeverityRow> rows = jdbcTemplate.query(sql, built.params().toArray(), (rs, rowNum) ->
                new WeaponSeverityRow(rs.getString("label"), rs.getBigDecimal("avg_fatalities"), rs.getBigDecimal("avg_injuries")));

        List<String> categories = rows.stream().map(WeaponSeverityRow::label).toList();
        List<BigDecimal> avgFatalities = rows.stream().map(WeaponSeverityRow::avgFatalities).toList();
        List<BigDecimal> avgInjuries = rows.stream().map(WeaponSeverityRow::avgInjuries).toList();

        return new ChartResponse("weaponSeverityChart", "Avg casualties per incident by weapon type",
                ChartResponse.ChartType.BAR, categories, List.of(
                        new ChartResponse.ChartSeries("Avg fatalities/incident", avgFatalities),
                        new ChartResponse.ChartSeries("Avg injuries/incident", avgInjuries)));
    }

    private record WeaponSeverityRow(String label, BigDecimal avgFatalities, BigDecimal avgInjuries) {
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

        String sql = "SELECT d.id AS entity_id, d.name AS label, SUM(mv.incident_count) AS total "
                + "FROM " + viewName + " mv "
                + "JOIN " + dimensionTable + " d ON d.id = mv." + dimensionIdColumn + " "
                + "WHERE " + yearWhere + unknownClause + " "
                + "GROUP BY d.id, d.name "
                + "ORDER BY total DESC";

        return jdbcTemplate.query(sql, params.toArray(), (rs, rowNum) ->
                new CategoryCount(rs.getLong("entity_id"), rs.getString("label"), rs.getBigDecimal("total")));
    }

    private record CategoryCount(long entityId, String label, BigDecimal value) {
    }
}

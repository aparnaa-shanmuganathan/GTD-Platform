package com.gtd.platform.service.insight;

import com.gtd.platform.service.trend.GrowthRateCalculator;
import com.gtd.platform.service.trend.GrowthRateCalculator.GrowthRateResult;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.InsightsResponse;
import com.gtd.platform.shared.api.InsightsResponse.InsightItem;
import com.gtd.platform.shared.api.InsightsResponse.Severity;
import com.gtd.platform.shared.db.YearRangeSqlHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * INSIGHT: real, computed narrative items for the Executive Workspace's
 * Insights panel (CAC-AN-005) - never hardcoded text. Generates exactly the
 * two insight types the Phase 4 prompt asks for:
 *
 * <ul>
 *   <li><strong>Growth-rate deviation callout</strong>: which non-unknown
 *   attack type (AWS-UNK-001) deviates most from its own trailing 5-year
 *   growth-rate baseline (SAV-008, {@link GrowthRateCalculator} - the single
 *   computation owner, reused here rather than reimplemented).</li>
 *   <li><strong>Ranking delta callout</strong>: which countries newly enter
 *   the top-10-by-incident-count ranking (CAC-AN-002) when the cutoff year
 *   advances from (latest year - 1) to the latest year.</li>
 * </ul>
 *
 * <p>Only the date-range half of the canonical filter DTO applies - the same
 * scope as {@code service.agg.RankingAndBreakdownService}, and for the same
 * reason (the materialized views these insights are computed from only
 * carry a year dimension alongside their own primary dimension). See
 * CLAUDE.md section 12.
 */
@Service
public class InsightService {

    private static final int TOP_N = 10;

    private final JdbcTemplate jdbcTemplate;

    public InsightService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public InsightsResponse generateExecutiveInsights(AnalyticalFilterRequest filter) {
        List<InsightItem> items = new ArrayList<>();
        growthRateDeviationInsight(filter).ifPresent(items::add);
        rankingDeltaInsight(filter).ifPresent(items::add);
        return new InsightsResponse("execInsights", items);
    }

    private Optional<InsightItem> growthRateDeviationInsight(AnalyticalFilterRequest filter) {
        Map<String, SortedMap<Integer, BigDecimal>> seriesByAttackType = attackTypeYearlySeries(filter);

        String deviatingLabel = null;
        GrowthRateResult deviatingResult = null;
        for (Map.Entry<String, SortedMap<Integer, BigDecimal>> entry : seriesByAttackType.entrySet()) {
            Optional<GrowthRateResult> result = GrowthRateCalculator.compute(entry.getValue());
            if (result.isEmpty()) {
                continue;
            }
            if (deviatingResult == null
                    || result.get().growthRatePercent().abs().compareTo(deviatingResult.growthRatePercent().abs()) > 0) {
                deviatingLabel = entry.getKey();
                deviatingResult = result.get();
            }
        }
        if (deviatingResult == null) {
            return Optional.empty();
        }

        String direction = deviatingResult.growthRatePercent().compareTo(BigDecimal.ZERO) >= 0 ? "above" : "below";
        BigDecimal absPercent = deviatingResult.growthRatePercent().abs().setScale(0, RoundingMode.HALF_UP);
        String text = String.format("<b>%s</b> incidents are running <b>%s%% %s</b> their trailing 5-year growth-rate baseline.",
                escapeHtml(deviatingLabel), absPercent.toPlainString(), direction);
        return Optional.of(new InsightItem(Severity.INFO, text, "Growth Rate deviation · SVC-TREND · SAV-008"));
    }

    private Optional<InsightItem> rankingDeltaInsight(AnalyticalFilterRequest filter) {
        Integer latestYear = queryLatestCountryYear(filter);
        if (latestYear == null) {
            return Optional.empty();
        }

        Set<String> currentTop = topCountryNamesThroughYear(filter, latestYear);
        Set<String> priorTop = topCountryNamesThroughYear(filter, latestYear - 1);
        if (priorTop.isEmpty()) {
            return Optional.empty();
        }

        List<String> newEntrants = currentTop.stream().filter(name -> !priorTop.contains(name)).sorted().toList();
        if (newEntrants.isEmpty()) {
            return Optional.empty();
        }

        String text = (newEntrants.size() == 1)
                ? String.format("<b>%s</b> entered the top-%d country ranking for the first time this year.",
                        escapeHtml(newEntrants.get(0)), TOP_N)
                : String.format("<b>%d countries</b> entered the top-%d country ranking for the first time this year, including %s.",
                        newEntrants.size(), TOP_N,
                        escapeHtml(String.join(", ", newEntrants.subList(0, Math.min(3, newEntrants.size())))));
        return Optional.of(new InsightItem(Severity.FLAG, text, "Ranking widget delta · CAC-AN-002"));
    }

    private Map<String, SortedMap<Integer, BigDecimal>> attackTypeYearlySeries(AnalyticalFilterRequest filter) {
        List<Object> params = new ArrayList<>();
        String yearWhere = YearRangeSqlHelper.whereClause(filter, params, "mv.event_year");
        String sql = "SELECT d.name AS label, mv.event_year AS year, SUM(mv.incident_count) AS total "
                + "FROM mv_attacktype_year_agg mv "
                + "JOIN dim_attack_type d ON d.id = mv.attack_type_id "
                + "WHERE d.is_unknown = FALSE AND " + yearWhere + " "
                + "GROUP BY d.name, mv.event_year "
                + "ORDER BY d.name, mv.event_year";

        Map<String, SortedMap<Integer, BigDecimal>> series = new LinkedHashMap<>();
        jdbcTemplate.query(sql, params.toArray(), rs -> {
            String label = rs.getString("label");
            int year = rs.getInt("year");
            BigDecimal total = rs.getBigDecimal("total");
            series.computeIfAbsent(label, k -> new TreeMap<>()).put(year, total);
        });
        return series;
    }

    private Integer queryLatestCountryYear(AnalyticalFilterRequest filter) {
        List<Object> params = new ArrayList<>();
        String yearWhere = YearRangeSqlHelper.whereClause(filter, params, "event_year");
        String sql = "SELECT MAX(event_year) FROM mv_country_year_agg WHERE " + yearWhere;
        Integer result = jdbcTemplate.queryForObject(sql, params.toArray(), Integer.class);
        return result;
    }

    private Set<String> topCountryNamesThroughYear(AnalyticalFilterRequest filter, int throughYearInclusive) {
        List<Object> params = new ArrayList<>();
        String yearWhere = YearRangeSqlHelper.whereClause(filter, params, "mv.event_year");
        params.add(throughYearInclusive);

        String sql = "SELECT d.name AS label, SUM(mv.incident_count) AS total "
                + "FROM mv_country_year_agg mv "
                + "JOIN dim_country d ON d.id = mv.country_id "
                + "WHERE " + yearWhere + " AND mv.event_year <= ? "
                + "GROUP BY d.name "
                + "ORDER BY total DESC "
                + "LIMIT " + TOP_N;

        List<String> names = jdbcTemplate.query(sql, params.toArray(), (rs, rowNum) -> rs.getString("label"));
        return new LinkedHashSet<>(names);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

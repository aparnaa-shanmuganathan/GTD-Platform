package com.gtd.platform.service.stat;

import com.gtd.platform.service.agg.RankingAndBreakdownService;
import com.gtd.platform.service.trend.TrendService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.RankingResponse;
import com.gtd.platform.shared.api.RankingResponse.RankingEntry;
import com.gtd.platform.shared.api.StatisticalSummaryResponse;
import com.gtd.platform.shared.api.StatisticalSummaryResponse.PercentileEntry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * SVC-STAT: the Statistical Workspace's five exclusive statistics (CLAUDE.md section 6 - Trend
 * Slope, Moving Average, Percentile Ranking, Correlation, Outlier Detection belong exclusively
 * here; no other workspace computes them). Orchestrates the five pure calculators in this
 * package against data sourced entirely from already-existing, already-tested services -
 * {@link TrendService} (yearly series) and {@link RankingAndBreakdownService} (the country
 * distribution Percentile Ranking ranks within) - so this class owns only the statistical
 * operations themselves, never a second copy of data-sourcing logic those services already own.
 */
@Service
public class StatisticalAnalysisService {

    /** How many top-ranked countries to show with their percentile position. */
    private static final int PERCENTILE_DISPLAY_COUNT = 10;

    /** Comfortably above the real/seeded country universe, so the percentile distribution is the true full set, not a truncated one. */
    private static final int ALL_COUNTRIES_LIMIT = 1000;

    private final TrendService trendService;
    private final RankingAndBreakdownService rankingAndBreakdownService;

    public StatisticalAnalysisService(TrendService trendService, RankingAndBreakdownService rankingAndBreakdownService) {
        this.trendService = trendService;
        this.rankingAndBreakdownService = rankingAndBreakdownService;
    }

    public StatisticalSummaryResponse computeSummary(AnalyticalFilterRequest filter) {
        SortedMap<Integer, BigDecimal> incidentsByYear = trendService.incidentCountByYear(filter);

        return new StatisticalSummaryResponse(
                "statSummary",
                trendWithMovingAverageChart(incidentsByYear),
                TrendSlopeCalculator.compute(incidentsByYear).orElse(null),
                OutlierDetector.detectOutlierYears(incidentsByYear),
                correlationCoefficient(filter),
                "Fatalities vs injuries by year",
                percentileRanking(filter)
        );
    }

    /** SAV "Moving Average" charted alongside the raw incidents-by-year series it's derived from (CAC-AN-003, reused shape). */
    private ChartResponse trendWithMovingAverageChart(SortedMap<Integer, BigDecimal> incidentsByYear) {
        SortedMap<Integer, BigDecimal> movingAverage =
                MovingAverageCalculator.compute(incidentsByYear, MovingAverageCalculator.DEFAULT_WINDOW_YEARS);

        List<String> categories = incidentsByYear.keySet().stream().map(String::valueOf).toList();
        List<BigDecimal> rawValues = new ArrayList<>(incidentsByYear.values());
        // movingAverage has exactly the same key set as incidentsByYear (MovingAverageCalculator
        // produces one output per input entry), so iterating in the same sorted key order keeps
        // both series positionally aligned with `categories`.
        List<BigDecimal> movingAverageValues = new ArrayList<>(movingAverage.values());

        return new ChartResponse("statTrendChart", "Incidents by year, with " + MovingAverageCalculator.DEFAULT_WINDOW_YEARS + "-year moving average",
                ChartResponse.ChartType.LINE, categories, List.of(
                        new ChartResponse.ChartSeries("Incidents", rawValues),
                        new ChartResponse.ChartSeries("Moving average", movingAverageValues)));
    }

    /**
     * SAV "Correlation" between yearly fatalities and yearly injuries. The two source series
     * ({@code fatalitiesByYear}/{@code injuriesByYear}) can each have gaps a plain {@code
     * incidentCountByYear} wouldn't (a year where every incident's {@code nkill} happens to be
     * unknown never appears in the fatalities series at all, {@code SUM()} over an all-NULL
     * group - CLAUDE.md sections 7/8), so correlation is computed only over years present in
     * <em>both</em> series, index-aligned by year - never a fabricated 0 for a missing year.
     */
    private BigDecimal correlationCoefficient(AnalyticalFilterRequest filter) {
        SortedMap<Integer, BigDecimal> fatalitiesByYear = trendService.fatalitiesByYear(filter);
        SortedMap<Integer, BigDecimal> injuriesByYear = trendService.injuriesByYear(filter);

        SortedSet<Integer> commonYears = new TreeSet<>(fatalitiesByYear.keySet());
        commonYears.retainAll(injuriesByYear.keySet());

        List<BigDecimal> fatalities = new ArrayList<>();
        List<BigDecimal> injuries = new ArrayList<>();
        for (Integer year : commonYears) {
            fatalities.add(fatalitiesByYear.get(year));
            injuries.add(injuriesByYear.get(year));
        }

        return CorrelationCalculator.compute(fatalities, injuries).orElse(null);
    }

    /** SAV "Percentile Ranking" of the top {@link #PERCENTILE_DISPLAY_COUNT} countries by incident count, against the full country distribution. */
    private List<PercentileEntry> percentileRanking(AnalyticalFilterRequest filter) {
        RankingResponse allCountries = rankingAndBreakdownService.topCountriesByIncidentCount(filter, ALL_COUNTRIES_LIMIT);
        if (allCountries.entries().isEmpty()) {
            return List.of();
        }

        List<BigDecimal> distribution = allCountries.entries().stream().map(RankingEntry::value).toList();

        List<PercentileEntry> result = new ArrayList<>();
        for (RankingEntry entry : allCountries.entries().subList(0, Math.min(PERCENTILE_DISPLAY_COUNT, allCountries.entries().size()))) {
            BigDecimal percentile = PercentileRankingCalculator.percentileRank(distribution, entry.value());
            result.add(new PercentileEntry(entry.label(), entry.entityId(), entry.value(), percentile));
        }
        return result;
    }
}

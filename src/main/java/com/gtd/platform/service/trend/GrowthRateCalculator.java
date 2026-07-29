package com.gtd.platform.service.trend;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

/**
 * SAV-008 Growth Rate: the platform's single computation owner (CLAUDE.md
 * section 6 - no other workspace/service recomputes this). Defined, per the
 * user's explicit decision for this phase, as the latest year's value
 * compared against the average of up to the trailing 5 years present in the
 * series - matching the approved UI reference's displayed comparison basis
 * ("+11.4% vs prior 5yr avg", "trailing 5-year growth-rate baseline").
 *
 * <p>A pure function with no dependencies (no DB, no Spring) specifically so
 * the formula itself is trivially unit-testable against hand-computed
 * values, independent of how any caller sources its yearly series.
 */
public final class GrowthRateCalculator {

    private static final int BASELINE_WINDOW_YEARS = 5;
    private static final int SCALE = 6;

    private GrowthRateCalculator() {
    }

    /**
     * @param yearlySeries year -&gt; value, must be pre-aggregated by caller (e.g. one row per year)
     * @return empty if the series has no year prior to the latest one to form a baseline from,
     *         or if that baseline average is exactly zero (growth rate from a zero baseline is undefined)
     */
    public static Optional<GrowthRateResult> compute(SortedMap<Integer, BigDecimal> yearlySeries) {
        if (yearlySeries.isEmpty()) {
            return Optional.empty();
        }
        int latestYear = yearlySeries.lastKey();
        BigDecimal latestValue = yearlySeries.get(latestYear);

        List<BigDecimal> priorValues = yearlySeries.entrySet().stream()
                .filter(entry -> entry.getKey() < latestYear)
                .sorted(Map.Entry.<Integer, BigDecimal>comparingByKey().reversed())
                .limit(BASELINE_WINDOW_YEARS)
                .map(Map.Entry::getValue)
                .toList();

        if (priorValues.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal baselineAverage = priorValues.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(priorValues.size()), SCALE, RoundingMode.HALF_UP);

        if (baselineAverage.compareTo(BigDecimal.ZERO) == 0) {
            return Optional.empty();
        }

        BigDecimal growthRatePercent = latestValue.subtract(baselineAverage)
                .divide(baselineAverage, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return Optional.of(new GrowthRateResult(latestYear, latestValue, baselineAverage, growthRatePercent, priorValues.size()));
    }

    /**
     * @param latestYear        the most recent year in the series
     * @param latestValue       that year's value
     * @param baselineAverage   the average of up to the 5 preceding years actually present in the series
     * @param growthRatePercent {@code (latestValue - baselineAverage) / baselineAverage * 100}
     * @param baselineYearCount how many prior years actually contributed to {@code baselineAverage} (1-5)
     */
    public record GrowthRateResult(
            int latestYear,
            BigDecimal latestValue,
            BigDecimal baselineAverage,
            BigDecimal growthRatePercent,
            int baselineYearCount
    ) {
    }
}

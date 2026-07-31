package com.gtd.platform.service.stat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * SAV "Moving Average" (CLAUDE.md section 6) - the single computation owner across the whole
 * platform. Belongs exclusively to SVC-STAT/Statistical Workspace.
 *
 * <p>Pure function, no DB/Spring dependency - mirrors {@code
 * com.gtd.platform.service.trend.GrowthRateCalculator}'s style. {@code
 * StatisticalAnalysisService} sources the input series from the already-existing {@code
 * TrendService.incidentCountByYear}, so this class owns only the averaging itself.
 */
public final class MovingAverageCalculator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    /** Default trailing window (years) used across the Statistical Workspace - a fixed default, not admin-configurable this phase. */
    public static final int DEFAULT_WINDOW_YEARS = 3;

    private MovingAverageCalculator() {
    }

    /**
     * Trailing {@code windowSize}-entry moving average, one output value per input entry, in
     * the series' own key order.
     *
     * <p>The window is positional (the up-to-{@code windowSize} most recent <em>entries present
     * in the map</em>), not a calendar-year window - a year with zero incidents never appears in
     * a yearly series sourced from {@code fact_incident} (no rows to {@code GROUP BY}), so a
     * calendar-based window could silently skip over a real gap year. The same convention {@code
     * GrowthRateCalculator}'s own trailing-baseline average already uses.
     *
     * <p>Entries before a full window has accumulated use however many trailing entries are
     * actually available (a partial window) rather than being omitted - every input year gets an
     * output value, so the resulting series is exactly as long as the input and safe to chart
     * alongside it.
     *
     * @param windowSize must be &gt;= 1
     */
    public static SortedMap<Integer, BigDecimal> compute(SortedMap<Integer, BigDecimal> series, int windowSize) {
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be >= 1, was " + windowSize);
        }

        SortedMap<Integer, BigDecimal> result = new TreeMap<>();
        List<Integer> years = new ArrayList<>(series.keySet());

        for (int i = 0; i < years.size(); i++) {
            int windowStart = Math.max(0, i - windowSize + 1);
            BigDecimal sum = BigDecimal.ZERO;
            int count = 0;
            for (int j = windowStart; j <= i; j++) {
                sum = sum.add(series.get(years.get(j)));
                count++;
            }
            result.put(years.get(i), sum.divide(BigDecimal.valueOf(count), MC));
        }
        return result;
    }
}

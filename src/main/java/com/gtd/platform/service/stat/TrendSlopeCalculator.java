package com.gtd.platform.service.stat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

/**
 * SAV "Trend Slope" (CLAUDE.md section 6) - the single computation owner across the whole
 * platform. Belongs exclusively to SVC-STAT/Statistical Workspace; no other workspace may
 * compute this (Insights panels elsewhere use SVC-TREND's simpler Growth Rate deviation
 * instead - CLAUDE.md section 6).
 *
 * <p>Pure function, no DB/Spring dependency - mirrors {@code
 * com.gtd.platform.service.trend.GrowthRateCalculator}'s style so the formula is directly
 * unit-testable against hand-computed values, independent of how a caller sources its yearly
 * series (here, {@code StatisticalAnalysisService} sources it from {@code
 * TrendService.incidentCountByYear}, reused rather than re-queried).
 */
public final class TrendSlopeCalculator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private TrendSlopeCalculator() {
    }

    /**
     * Ordinary least-squares linear regression slope of {@code value} against {@code year}:
     * {@code slope = (n*Sum(xy) - Sum(x)*Sum(y)) / (n*Sum(x^2) - Sum(x)^2)}, x = year, y = value.
     *
     * @return the slope, or {@link Optional#empty()} if the series has fewer than 2 points (a
     *         single point has no meaningful trend, the same threshold {@code GrowthRateCalculator}
     *         uses for its own baseline requirement)
     */
    public static Optional<BigDecimal> compute(SortedMap<Integer, BigDecimal> series) {
        if (series.size() < 2) {
            return Optional.empty();
        }

        int n = series.size();
        BigDecimal sumX = BigDecimal.ZERO;
        BigDecimal sumY = BigDecimal.ZERO;
        BigDecimal sumXy = BigDecimal.ZERO;
        BigDecimal sumX2 = BigDecimal.ZERO;

        for (Map.Entry<Integer, BigDecimal> entry : series.entrySet()) {
            BigDecimal x = BigDecimal.valueOf(entry.getKey());
            BigDecimal y = entry.getValue();
            sumX = sumX.add(x);
            sumY = sumY.add(y);
            sumXy = sumXy.add(x.multiply(y));
            sumX2 = sumX2.add(x.multiply(x));
        }

        BigDecimal nBd = BigDecimal.valueOf(n);
        BigDecimal numerator = nBd.multiply(sumXy).subtract(sumX.multiply(sumY));
        BigDecimal denominator = nBd.multiply(sumX2).subtract(sumX.multiply(sumX));
        // denominator is zero only if every x is identical, impossible here: series keys are
        // distinct years (SortedMap), and n >= 2 guarantees at least two distinct x values.
        return Optional.of(numerator.divide(denominator, MC));
    }
}

package com.gtd.platform.service.stat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * SAV "Correlation" (CLAUDE.md section 6) - the single computation owner across the whole
 * platform. Belongs exclusively to SVC-STAT/Statistical Workspace.
 *
 * <p>Pure function, no DB/Spring dependency, mirroring {@code
 * com.gtd.platform.service.trend.GrowthRateCalculator}'s style. {@code StatisticalAnalysisService}
 * sources the two series from the already-existing {@code TrendService.fatalitiesByYear}/{@code
 * TrendService.injuriesByYear} (aligned by year), so this class owns only the correlation
 * formula itself.
 */
public final class CorrelationCalculator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private CorrelationCalculator() {
    }

    /**
     * Pearson product-moment correlation coefficient: {@code r = Sum((x-xMean)(y-yMean)) /
     * sqrt(Sum((x-xMean)^2) * Sum((y-yMean)^2))}.
     *
     * @param x and {@code y} must be the same length and index-aligned (the value at index
     *          {@code i} in each list must correspond to the same underlying observation, e.g.
     *          the same year)
     * @return {@code r} in {@code [-1, 1]}, or {@link Optional#empty()} if there are fewer than
     *         2 points or either series has zero variance (a constant series has no meaningful
     *         correlation - the formula's denominator would be zero)
     */
    public static Optional<BigDecimal> compute(List<BigDecimal> x, List<BigDecimal> y) {
        if (x.size() != y.size()) {
            throw new IllegalArgumentException("x and y must be the same length: " + x.size() + " vs " + y.size());
        }
        if (x.size() < 2) {
            return Optional.empty();
        }

        BigDecimal n = BigDecimal.valueOf(x.size());
        BigDecimal xMean = sum(x).divide(n, MC);
        BigDecimal yMean = sum(y).divide(n, MC);

        BigDecimal sumXyDev = BigDecimal.ZERO;
        BigDecimal sumXDev2 = BigDecimal.ZERO;
        BigDecimal sumYDev2 = BigDecimal.ZERO;
        for (int i = 0; i < x.size(); i++) {
            BigDecimal xDev = x.get(i).subtract(xMean);
            BigDecimal yDev = y.get(i).subtract(yMean);
            sumXyDev = sumXyDev.add(xDev.multiply(yDev));
            sumXDev2 = sumXDev2.add(xDev.multiply(xDev));
            sumYDev2 = sumYDev2.add(yDev.multiply(yDev));
        }

        if (sumXDev2.signum() == 0 || sumYDev2.signum() == 0) {
            return Optional.empty();
        }

        double denominator = Math.sqrt(sumXDev2.multiply(sumYDev2, MC).doubleValue());
        return Optional.of(sumXyDev.divide(BigDecimal.valueOf(denominator), MC));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return total;
    }
}

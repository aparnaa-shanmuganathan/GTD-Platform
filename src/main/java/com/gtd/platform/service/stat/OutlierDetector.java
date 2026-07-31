package com.gtd.platform.service.stat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * SAV "Outlier Detection" (CLAUDE.md section 6) - the single computation owner across the
 * whole platform. Belongs exclusively to SVC-STAT/Statistical Workspace.
 *
 * <p>Pure function, no DB/Spring dependency, mirroring {@code
 * com.gtd.platform.service.trend.GrowthRateCalculator}'s style. {@code StatisticalAnalysisService}
 * sources the input series from the already-existing {@code TrendService.incidentCountByYear}.
 *
 * <p>Z-score method: a year is an outlier if its value is more than {@link #DEFAULT_Z_THRESHOLD}
 * sample standard deviations from the series mean. Sample standard deviation (Bessel's
 * correction, {@code n-1} denominator) is used rather than population standard deviation, the
 * standard choice for a finite observed sample rather than a known full population.
 */
public final class OutlierDetector {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    /** Fixed default Z-score threshold - not admin-configurable this phase. */
    public static final double DEFAULT_Z_THRESHOLD = 2.0;

    private OutlierDetector() {
    }

    /**
     * @return the years whose value's absolute Z-score exceeds {@link #DEFAULT_Z_THRESHOLD}, in
     *         ascending year order. Empty (never throws) if the series has fewer than 2 points
     *         (standard deviation is undefined for a single point) or the series has zero
     *         variance (every value identical - nothing can be an outlier).
     */
    public static List<Integer> detectOutlierYears(SortedMap<Integer, BigDecimal> series) {
        if (series.size() < 2) {
            return List.of();
        }

        BigDecimal n = BigDecimal.valueOf(series.size());
        BigDecimal mean = BigDecimal.ZERO;
        for (BigDecimal value : series.values()) {
            mean = mean.add(value);
        }
        mean = mean.divide(n, MC);

        BigDecimal sumSquaredDeviations = BigDecimal.ZERO;
        for (BigDecimal value : series.values()) {
            BigDecimal deviation = value.subtract(mean);
            sumSquaredDeviations = sumSquaredDeviations.add(deviation.multiply(deviation));
        }
        BigDecimal sampleVariance = sumSquaredDeviations.divide(n.subtract(BigDecimal.ONE), MC);
        if (sampleVariance.signum() == 0) {
            return List.of();
        }
        double stdDev = Math.sqrt(sampleVariance.doubleValue());

        List<Integer> outliers = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> entry : series.entrySet()) {
            double z = entry.getValue().subtract(mean).doubleValue() / stdDev;
            if (Math.abs(z) > DEFAULT_Z_THRESHOLD) {
                outliers.add(entry.getKey());
            }
        }
        return outliers;
    }
}

package com.gtd.platform.service.stat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * SAV "Percentile Ranking" (CLAUDE.md section 6) - the single computation owner across the
 * whole platform. Belongs exclusively to SVC-STAT/Statistical Workspace.
 *
 * <p>Pure function, no DB/Spring dependency, mirroring {@code
 * com.gtd.platform.service.trend.GrowthRateCalculator}'s style. {@code StatisticalAnalysisService}
 * sources the distribution to rank within from the already-existing {@code
 * RankingAndBreakdownService.topCountriesByIncidentCount}, so this class owns only the
 * percentile-rank formula itself.
 */
public final class PercentileRankingCalculator {

    private static final MathContext MC = new MathContext(6, RoundingMode.HALF_UP);

    private PercentileRankingCalculator() {
    }

    /**
     * The standard "mean percentile rank" of {@code target} within {@code distribution}:
     * {@code (countBelow + 0.5 * countEqual) / total * 100}. Values exactly equal to {@code
     * target} (including {@code target} itself) are counted as "half in, half out" - the usual
     * convention that makes the median of a distribution land at (or very near) the 50th
     * percentile rather than skewing high or low based on tie-handling.
     *
     * @param distribution must be non-empty
     * @return a value in {@code [0, 100]}
     */
    public static BigDecimal percentileRank(List<BigDecimal> distribution, BigDecimal target) {
        if (distribution.isEmpty()) {
            throw new IllegalArgumentException("distribution must not be empty");
        }

        long countBelow = distribution.stream().filter(v -> v.compareTo(target) < 0).count();
        long countEqual = distribution.stream().filter(v -> v.compareTo(target) == 0).count();

        BigDecimal numerator = BigDecimal.valueOf(countBelow)
                .add(BigDecimal.valueOf(countEqual).multiply(new BigDecimal("0.5")));
        return numerator
                .divide(BigDecimal.valueOf(distribution.size()), MC)
                .multiply(BigDecimal.valueOf(100));
    }
}

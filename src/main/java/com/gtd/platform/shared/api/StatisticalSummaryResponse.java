package com.gtd.platform.shared.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * CAC-AN-006 (CLAUDE.md section 9): the shared statistical summary component's response shape -
 * deferred since Phase 2 to whichever phase first needed it (Phase 2's own decision log: "CAC-AN-004
 * Map and CAC-AN-006 Statistical Summary are deferred to whichever phase first needs them,
 * Geospatial/Statistical"). Phase 9's Statistical Workspace is that phase.
 *
 * <p>One cohesive response covering all five of SVC-STAT's exclusive statistics (CLAUDE.md
 * section 6: Trend Slope, Moving Average, Percentile Ranking, Correlation, Outlier Detection -
 * no other workspace may compute these).
 *
 * @param id                     a stable identifier for the statistical summary panel
 * @param trendWithMovingAverage CAC-AN-003 reused: incidents-by-year (SVC-TREND, already exists) plus
 *                               the Moving Average series (SAV "Moving Average") as a second line
 * @param trendSlope             SAV "Trend Slope" of the incidents-by-year series, or {@code null} if undefined (fewer than 2 years of data)
 * @param outlierYears           SAV "Outlier Detection" - years whose incident count is a statistical outlier
 * @param correlationCoefficient SAV "Correlation" (Pearson's r), or {@code null} if undefined
 * @param correlationDescription what {@code correlationCoefficient} measures (e.g. {@code "Fatalities vs injuries by year"})
 * @param percentileRanking      SAV "Percentile Ranking" - a ranked distribution with each entry's percentile position
 */
public record StatisticalSummaryResponse(
        String id,
        ChartResponse trendWithMovingAverage,
        BigDecimal trendSlope,
        List<Integer> outlierYears,
        BigDecimal correlationCoefficient,
        String correlationDescription,
        List<PercentileEntry> percentileRanking
) {

    /**
     * @param label      the ranked entity's display name (e.g. a country name)
     * @param entityId   the ranked entity's surrogate id
     * @param value      the value it's ranked by
     * @param percentile this entry's percentile position (0-100) within the full distribution
     */
    public record PercentileEntry(String label, Long entityId, BigDecimal value, BigDecimal percentile) {
    }
}

package com.gtd.platform.service.trend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of {@link GrowthRateCalculator} (SAV-008) against
 * hand-computed values - the single computation owner for Growth Rate
 * across the whole platform (CLAUDE.md section 6), so getting this exactly
 * right matters more than any one caller of it.
 */
class GrowthRateCalculatorTest {

    @Test
    void computesLatestYearVersusTrailingFiveYearAverage() {
        // 2018:100, 2019:120, 2020:90, 2021:150, 2022:200 (5 years total,
        // only 4 available as baseline since the series itself only spans 5 years)
        SortedMap<Integer, BigDecimal> series = series(
                2018, "100", 2019, "120", 2020, "90", 2021, "150", 2022, "200");

        Optional<GrowthRateCalculator.GrowthRateResult> result = GrowthRateCalculator.compute(series);

        assertThat(result).isPresent();
        GrowthRateCalculator.GrowthRateResult value = result.get();
        assertThat(value.latestYear()).isEqualTo(2022);
        assertThat(value.latestValue()).isEqualByComparingTo("200");
        // baseline = (100+120+90+150)/4 = 115
        assertThat(value.baselineAverage()).isEqualByComparingTo("115");
        assertThat(value.baselineYearCount()).isEqualTo(4);
        // growthRate = (200-115)/115*100 = 73.913...%
        assertThat(value.growthRatePercent().doubleValue()).isCloseTo(73.913, offset(0.01));
    }

    @Test
    void usesOnlyTheMostRecentFiveYearsAsBaselineWhenMoreArePresent() {
        // 8 years present; baseline must use only the 5 immediately preceding
        // the latest year (2015-2019), not the earlier 2013/2014.
        SortedMap<Integer, BigDecimal> series = series(
                2013, "9999", 2014, "9999",
                2015, "100", 2016, "100", 2017, "100", 2018, "100", 2019, "100",
                2020, "200");

        GrowthRateCalculator.GrowthRateResult value = GrowthRateCalculator.compute(series).orElseThrow();

        assertThat(value.baselineYearCount()).isEqualTo(5);
        assertThat(value.baselineAverage()).isEqualByComparingTo("100");
        assertThat(value.growthRatePercent()).isEqualByComparingTo("100.000000");
    }

    @Test
    void negativeGrowthRateWhenLatestYearIsBelowBaseline() {
        SortedMap<Integer, BigDecimal> series = series(2020, "200", 2021, "100");

        GrowthRateCalculator.GrowthRateResult value = GrowthRateCalculator.compute(series).orElseThrow();

        assertThat(value.growthRatePercent()).isEqualByComparingTo("-50.000000");
    }

    @Test
    void emptySeriesIsUndefined() {
        assertThat(GrowthRateCalculator.compute(new TreeMap<>())).isEmpty();
    }

    @Test
    void singleYearSeriesIsUndefinedNoBaselineToCompareAgainst() {
        SortedMap<Integer, BigDecimal> series = series(2022, "500");
        assertThat(GrowthRateCalculator.compute(series)).isEmpty();
    }

    @Test
    void zeroBaselineAverageIsUndefinedAvoidsDivideByZero() {
        SortedMap<Integer, BigDecimal> series = series(2020, "0", 2021, "0", 2022, "50");
        assertThat(GrowthRateCalculator.compute(series)).isEmpty();
    }

    private static SortedMap<Integer, BigDecimal> series(Object... yearValuePairs) {
        SortedMap<Integer, BigDecimal> map = new TreeMap<>();
        for (int i = 0; i < yearValuePairs.length; i += 2) {
            map.put((Integer) yearValuePairs[i], new BigDecimal((String) yearValuePairs[i + 1]));
        }
        return map;
    }
}

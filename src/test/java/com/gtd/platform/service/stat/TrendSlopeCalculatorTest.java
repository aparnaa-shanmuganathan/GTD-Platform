package com.gtd.platform.service.stat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of {@link TrendSlopeCalculator} (SAV "Trend Slope") against hand-computed
 * values - the single computation owner across the whole platform, so getting the formula
 * exactly right matters more than any one caller of it, the same rigor {@code
 * GrowthRateCalculatorTest} holds SAV-008 to.
 */
class TrendSlopeCalculatorTest {

    @Test
    void perfectLinearSeriesYieldsExactSlope() {
        // 2015-2018: 10, 20, 30, 40 - a perfectly straight line, delta 10/year.
        SortedMap<Integer, BigDecimal> series = series(2015, "10", 2016, "20", 2017, "30", 2018, "40");
        Optional<BigDecimal> slope = TrendSlopeCalculator.compute(series);
        assertThat(slope).isPresent();
        assertThat(slope.get()).isEqualByComparingTo("10");
    }

    @Test
    void twoPointSeriesIsTheSimpleSlopeBetweenThem() {
        SortedMap<Integer, BigDecimal> series = series(2020, "5", 2021, "15");
        Optional<BigDecimal> slope = TrendSlopeCalculator.compute(series);
        assertThat(slope).isPresent();
        assertThat(slope.get()).isEqualByComparingTo("10");
    }

    @Test
    void noisySeriesMatchesHandComputedSlope() {
        // 2015-2019: 10, 12, 11, 15, 17 - hand-computed OLS slope = 17/10 = 1.7
        // (mean y = 13; centering x around 2017 gives x' = -2,-1,0,1,2, sum x'=0;
        // Sum(x'*(y-yMean)) = 6+1+0+2+8 = 17; Sum(x'^2) = 4+1+0+1+4 = 10).
        SortedMap<Integer, BigDecimal> series = series(2015, "10", 2016, "12", 2017, "11", 2018, "15", 2019, "17");
        Optional<BigDecimal> slope = TrendSlopeCalculator.compute(series);
        assertThat(slope).isPresent();
        assertThat(slope.get().doubleValue()).isCloseTo(1.7, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void negativeSlopeIsDetectedCorrectly() {
        SortedMap<Integer, BigDecimal> series = series(2020, "100", 2021, "80", 2022, "60");
        Optional<BigDecimal> slope = TrendSlopeCalculator.compute(series);
        assertThat(slope).isPresent();
        assertThat(slope.get()).isEqualByComparingTo("-20");
    }

    @Test
    void singlePointSeriesIsUndefined() {
        assertThat(TrendSlopeCalculator.compute(series(2020, "100"))).isEmpty();
    }

    @Test
    void emptySeriesIsUndefined() {
        assertThat(TrendSlopeCalculator.compute(new TreeMap<>())).isEmpty();
    }

    private static SortedMap<Integer, BigDecimal> series(Object... yearValuePairs) {
        SortedMap<Integer, BigDecimal> map = new TreeMap<>();
        for (int i = 0; i < yearValuePairs.length; i += 2) {
            map.put((Integer) yearValuePairs[i], new BigDecimal((String) yearValuePairs[i + 1]));
        }
        return map;
    }
}

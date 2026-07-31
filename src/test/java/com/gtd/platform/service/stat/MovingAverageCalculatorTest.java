package com.gtd.platform.service.stat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of {@link MovingAverageCalculator} (SAV "Moving Average") against
 * hand-computed values, including the partial-window convention at the start of the series.
 */
class MovingAverageCalculatorTest {

    @Test
    void trailingThreeYearWindowUsesPartialWindowsAtTheStart() {
        // 2015-2019: 10, 20, 30, 40, 50
        SortedMap<Integer, BigDecimal> series = series(2015, "10", 2016, "20", 2017, "30", 2018, "40", 2019, "50");
        SortedMap<Integer, BigDecimal> movingAverage = MovingAverageCalculator.compute(series, 3);

        assertThat(movingAverage.get(2015)).isEqualByComparingTo("10");       // window: [10]
        assertThat(movingAverage.get(2016)).isEqualByComparingTo("15");       // window: [10,20]
        assertThat(movingAverage.get(2017)).isEqualByComparingTo("20");       // window: [10,20,30]
        assertThat(movingAverage.get(2018)).isEqualByComparingTo("30");       // window: [20,30,40]
        assertThat(movingAverage.get(2019)).isEqualByComparingTo("40");       // window: [30,40,50]
        assertThat(movingAverage.keySet()).containsExactly(2015, 2016, 2017, 2018, 2019);
    }

    @Test
    void windowOfOneReturnsTheSeriesUnchanged() {
        SortedMap<Integer, BigDecimal> series = series(2020, "5", 2021, "9");
        SortedMap<Integer, BigDecimal> movingAverage = MovingAverageCalculator.compute(series, 1);
        assertThat(movingAverage.get(2020)).isEqualByComparingTo("5");
        assertThat(movingAverage.get(2021)).isEqualByComparingTo("9");
    }

    @Test
    void windowLargerThanSeriesAveragesEverythingAvailable() {
        SortedMap<Integer, BigDecimal> series = series(2020, "10", 2021, "20");
        SortedMap<Integer, BigDecimal> movingAverage = MovingAverageCalculator.compute(series, 10);
        assertThat(movingAverage.get(2020)).isEqualByComparingTo("10");
        assertThat(movingAverage.get(2021)).isEqualByComparingTo("15"); // (10+20)/2
    }

    @Test
    void windowSizeBelowOneThrows() {
        SortedMap<Integer, BigDecimal> series = series(2020, "10");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MovingAverageCalculator.compute(series, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptySeriesYieldsEmptyResult() {
        assertThat(MovingAverageCalculator.compute(new TreeMap<>(), 3)).isEmpty();
    }

    private static SortedMap<Integer, BigDecimal> series(Object... yearValuePairs) {
        SortedMap<Integer, BigDecimal> map = new TreeMap<>();
        for (int i = 0; i < yearValuePairs.length; i += 2) {
            map.put((Integer) yearValuePairs[i], new BigDecimal((String) yearValuePairs[i + 1]));
        }
        return map;
    }
}

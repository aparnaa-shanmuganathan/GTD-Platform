package com.gtd.platform.service.stat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of {@link OutlierDetector} (SAV "Outlier Detection") against hand-computed
 * Z-scores.
 */
class OutlierDetectorTest {

    @Test
    void clearOutlierIsDetected() {
        // 2010-2015: six years at 10, then 2016 at 100.
        // mean = (60+100)/7 = 22.857142857; sample stddev = 34.017 (n-1 denominator).
        // z(2016) = (100-22.857)/34.017 = 2.268 > 2.0 -> outlier.
        // z(10s)  = (10-22.857)/34.017 = -0.378 -> not an outlier.
        SortedMap<Integer, BigDecimal> series = series(
                2010, "10", 2011, "10", 2012, "10", 2013, "10", 2014, "10", 2015, "10", 2016, "100");

        List<Integer> outliers = OutlierDetector.detectOutlierYears(series);

        assertThat(outliers).containsExactly(2016);
    }

    @Test
    void closelyClusteredSeriesHasNoOutliers() {
        SortedMap<Integer, BigDecimal> series = series(2020, "10", 2021, "11", 2022, "9", 2023, "10", 2024, "11");
        assertThat(OutlierDetector.detectOutlierYears(series)).isEmpty();
    }

    @Test
    void zeroVarianceSeriesHasNoOutliersNoDivideByZero() {
        SortedMap<Integer, BigDecimal> series = series(2020, "10", 2021, "10", 2022, "10");
        assertThat(OutlierDetector.detectOutlierYears(series)).isEmpty();
    }

    @Test
    void fewerThanTwoPointsHasNoOutliers() {
        assertThat(OutlierDetector.detectOutlierYears(series(2020, "10"))).isEmpty();
        assertThat(OutlierDetector.detectOutlierYears(new TreeMap<>())).isEmpty();
    }

    @Test
    void outlierOnTheLowSideIsAlsoDetected() {
        // Mirror image of the high-side test: one very low year among six normal ones.
        SortedMap<Integer, BigDecimal> series = series(
                2010, "100", 2011, "100", 2012, "100", 2013, "100", 2014, "100", 2015, "100", 2016, "10");
        assertThat(OutlierDetector.detectOutlierYears(series)).containsExactly(2016);
    }

    private static SortedMap<Integer, BigDecimal> series(Object... yearValuePairs) {
        SortedMap<Integer, BigDecimal> map = new TreeMap<>();
        for (int i = 0; i < yearValuePairs.length; i += 2) {
            map.put((Integer) yearValuePairs[i], new BigDecimal((String) yearValuePairs[i + 1]));
        }
        return map;
    }
}

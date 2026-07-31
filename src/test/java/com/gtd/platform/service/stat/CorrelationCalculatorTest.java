package com.gtd.platform.service.stat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of {@link CorrelationCalculator} (SAV "Correlation") against hand-computed
 * and well-known reference values (perfect positive/negative correlation, and a hand-derived
 * partial correlation).
 */
class CorrelationCalculatorTest {

    @Test
    void perfectPositiveCorrelationIsExactlyOne() {
        List<BigDecimal> x = bd(1, 2, 3, 4, 5);
        List<BigDecimal> y = bd(2, 4, 6, 8, 10); // y = 2x
        Optional<BigDecimal> r = CorrelationCalculator.compute(x, y);
        assertThat(r).isPresent();
        assertThat(r.get().doubleValue()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void perfectNegativeCorrelationIsExactlyMinusOne() {
        List<BigDecimal> x = bd(1, 2, 3, 4, 5);
        List<BigDecimal> y = bd(10, 8, 6, 4, 2);
        Optional<BigDecimal> r = CorrelationCalculator.compute(x, y);
        assertThat(r).isPresent();
        assertThat(r.get().doubleValue()).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void partialCorrelationMatchesHandComputedValue() {
        // x=[1,2,3,4], y=[2,1,4,3]. xMean=yMean=2.5.
        // xDev=[-1.5,-0.5,0.5,1.5], yDev=[-0.5,-1.5,1.5,0.5].
        // Sum(xDev*yDev) = 0.75*4 = 3.0; Sum(xDev^2)=Sum(yDev^2)=5.0 -> r = 3.0 / sqrt(5*5) = 0.6
        List<BigDecimal> x = bd(1, 2, 3, 4);
        List<BigDecimal> y = bd(2, 1, 4, 3);
        Optional<BigDecimal> r = CorrelationCalculator.compute(x, y);
        assertThat(r).isPresent();
        assertThat(r.get().doubleValue()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void zeroVarianceSeriesIsUndefined() {
        List<BigDecimal> x = bd(1, 2, 3);
        List<BigDecimal> yConstant = bd(5, 5, 5);
        assertThat(CorrelationCalculator.compute(x, yConstant)).isEmpty();
    }

    @Test
    void fewerThanTwoPointsIsUndefined() {
        assertThat(CorrelationCalculator.compute(bd(1), bd(2))).isEmpty();
        assertThat(CorrelationCalculator.compute(List.of(), List.of())).isEmpty();
    }

    @Test
    void mismatchedLengthsThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CorrelationCalculator.compute(bd(1, 2), bd(1, 2, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<BigDecimal> bd(int... values) {
        List<BigDecimal> list = new java.util.ArrayList<>();
        for (int v : values) {
            list.add(BigDecimal.valueOf(v));
        }
        return list;
    }
}

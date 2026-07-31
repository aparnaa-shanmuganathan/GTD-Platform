package com.gtd.platform.service.stat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of {@link PercentileRankingCalculator} (SAV "Percentile Ranking") against
 * hand-computed values, including tie-handling and the distribution's min/max boundaries.
 */
class PercentileRankingCalculatorTest {

    private static final List<BigDecimal> DISTRIBUTION =
            List.of(new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("40"), new BigDecimal("50"));

    @Test
    void middleValueIsFiftiethPercentile() {
        // 30: 2 below (10,20), 1 equal (itself) -> (2 + 0.5) / 5 * 100 = 50%
        assertThat(PercentileRankingCalculator.percentileRank(DISTRIBUTION, new BigDecimal("30")))
                .isEqualByComparingTo("50");
    }

    @Test
    void minimumValueIsLowPercentileNotZero() {
        // 10: 0 below, 1 equal -> (0 + 0.5) / 5 * 100 = 10%
        assertThat(PercentileRankingCalculator.percentileRank(DISTRIBUTION, new BigDecimal("10")))
                .isEqualByComparingTo("10");
    }

    @Test
    void maximumValueIsHighPercentileNotOneHundred() {
        // 50: 4 below, 1 equal -> (4 + 0.5) / 5 * 100 = 90%
        assertThat(PercentileRankingCalculator.percentileRank(DISTRIBUTION, new BigDecimal("50")))
                .isEqualByComparingTo("90");
    }

    @Test
    void targetNotInDistributionIsRankedByWhereItWouldFall() {
        // 25: 2 below (10,20), 0 equal -> (2 + 0) / 5 * 100 = 40%
        assertThat(PercentileRankingCalculator.percentileRank(DISTRIBUTION, new BigDecimal("25")))
                .isEqualByComparingTo("40");
    }

    @Test
    void tiedValuesSplitCreditEvenly() {
        // [10, 10, 20, 30], target 10: 0 below, 2 equal -> (0 + 1) / 4 * 100 = 25%
        List<BigDecimal> withTies = List.of(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"));
        assertThat(PercentileRankingCalculator.percentileRank(withTies, new BigDecimal("10")))
                .isEqualByComparingTo("25");
    }

    @Test
    void singleElementDistributionRanksItselfAtFiftiethPercentile() {
        List<BigDecimal> single = List.of(new BigDecimal("42"));
        assertThat(PercentileRankingCalculator.percentileRank(single, new BigDecimal("42")))
                .isEqualByComparingTo("50");
    }

    @Test
    void emptyDistributionThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> PercentileRankingCalculator.percentileRank(List.of(), new BigDecimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.gtd.platform.service.casualty;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.service.casualty.SeverityBandClassifier.SeverityBand;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of {@link SeverityBandClassifier} (SAV "Severity
 * Classification") against every boundary the approved thresholds define
 * (CLAUDE.md section 12, Phase 8 decisions) - the single computation owner
 * across the whole platform, so getting the boundaries exactly right
 * matters more than any one caller of it, the same rigor {@code
 * GrowthRateCalculatorTest} holds SAV-008 to.
 */
class SeverityBandClassifierTest {

    @Test
    void bothNullIsUnknownNeverSilentlyLow() {
        assertThat(SeverityBandClassifier.classify(null, null)).isEqualTo(SeverityBand.UNKNOWN);
    }

    @Test
    void oneNullOneZeroIsLow() {
        // Only one side known, and it's zero - the known total is 0, not "unknown".
        assertThat(SeverityBandClassifier.classify(0, null)).isEqualTo(SeverityBand.LOW);
        assertThat(SeverityBandClassifier.classify(null, 0)).isEqualTo(SeverityBand.LOW);
    }

    @Test
    void oneNullOneNonZeroSumsOnlyTheKnownSide() {
        // nkill unknown, nwound=5 known -> total=5 (MODERATE), not treated as unknown.
        assertThat(SeverityBandClassifier.classify(null, 5)).isEqualTo(SeverityBand.MODERATE);
        assertThat(SeverityBandClassifier.classify(5, null)).isEqualTo(SeverityBand.MODERATE);
    }

    @Test
    void zeroTotalIsLow() {
        assertThat(SeverityBandClassifier.classify(0, 0)).isEqualTo(SeverityBand.LOW);
    }

    @Test
    void lowModerateBoundary() {
        // LOW_MAX = 0: total 0 is the last LOW value, total 1 is the first MODERATE value.
        assertThat(SeverityBandClassifier.classify(0, 0)).isEqualTo(SeverityBand.LOW);
        assertThat(SeverityBandClassifier.classify(1, 0)).isEqualTo(SeverityBand.MODERATE);
        assertThat(SeverityBandClassifier.classify(0, 1)).isEqualTo(SeverityBand.MODERATE);
    }

    @Test
    void moderateHighBoundary() {
        // MODERATE_MAX = 10: total 10 is the last MODERATE value, total 11 is the first HIGH value.
        assertThat(SeverityBandClassifier.classify(5, 5)).isEqualTo(SeverityBand.MODERATE);
        assertThat(SeverityBandClassifier.classify(6, 5)).isEqualTo(SeverityBand.HIGH);
        assertThat(SeverityBandClassifier.classify(11, 0)).isEqualTo(SeverityBand.HIGH);
    }

    @Test
    void highCatastrophicBoundary() {
        // HIGH_MAX = 50: total 50 is the last HIGH value, total 51 is the first CATASTROPHIC value.
        assertThat(SeverityBandClassifier.classify(25, 25)).isEqualTo(SeverityBand.HIGH);
        assertThat(SeverityBandClassifier.classify(26, 25)).isEqualTo(SeverityBand.CATASTROPHIC);
        assertThat(SeverityBandClassifier.classify(51, 0)).isEqualTo(SeverityBand.CATASTROPHIC);
    }

    @Test
    void wellAboveCatastrophicThresholdIsStillCatastrophic() {
        assertThat(SeverityBandClassifier.classify(500, 300)).isEqualTo(SeverityBand.CATASTROPHIC);
    }
}

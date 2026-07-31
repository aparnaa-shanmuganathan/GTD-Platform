package com.gtd.platform.service.casualty;

/**
 * SAV "Severity Classification" (CLAUDE.md section 6) - the single computation owner for
 * classifying one incident's total casualties (fatalities + injuries) into a severity band.
 * Not in SVC-STAT's exclusive list (Trend Slope/Moving Average/Percentile Ranking/Correlation/
 * Outlier Detection - CLAUDE.md section 6), and unclaimed by any other workspace, so Casualty
 * Workspace (Phase 8) is its natural owner.
 *
 * <p>Pure function, no DB/Spring dependency - mirrors {@code
 * com.gtd.platform.service.trend.GrowthRateCalculator}'s style so the actual band-threshold
 * logic is directly unit-testable in isolation, independent of how a caller sources
 * {@code nkill}/{@code nwound}. {@link com.gtd.platform.service.casualty.CasualtyAggregationService}
 * builds a SQL {@code CASE WHEN} expression that mirrors this method's logic for the
 * incident-count-at-scale aggregate query, referencing {@link #LOW_MAX}/{@link #MODERATE_MAX}/
 * {@link #HIGH_MAX} directly when constructing that SQL - so the actual threshold numbers live
 * in exactly one place even though the classification rule itself is necessarily expressed
 * twice (Java here, generated SQL there).
 *
 * <p><strong>Thresholds are fixed defaults for this phase, not yet admin-configurable</strong> -
 * confirmed with the user (CLAUDE.md section 12, Phase 8 decisions): the original spec's
 * "admin-configurable severity bands" requirement is deferred to Phase 10's CONFIG service,
 * which is where these constants will eventually become editable settings rather than
 * {@code static final} values.
 */
public final class SeverityBandClassifier {

    private SeverityBandClassifier() {
    }

    /** Total casualties (fatalities + injuries) at or below this value classify as {@link SeverityBand#LOW}. */
    public static final int LOW_MAX = 0;

    /** Total casualties at or below this value (and above {@link #LOW_MAX}) classify as {@link SeverityBand#MODERATE}. */
    public static final int MODERATE_MAX = 10;

    /** Total casualties at or below this value (and above {@link #MODERATE_MAX}) classify as {@link SeverityBand#HIGH}; above it, {@link SeverityBand#CATASTROPHIC}. */
    public static final int HIGH_MAX = 50;

    public enum SeverityBand {
        /** Both {@code nkill} and {@code nwound} are unknown ({@code null}) - never silently treated as {@link #LOW} (CLAUDE.md sections 7/8: unknown is not zero). */
        UNKNOWN,
        LOW,
        MODERATE,
        HIGH,
        CATASTROPHIC
    }

    /**
     * @param nkill  fatalities for one incident, or {@code null} if unknown
     * @param nwound injuries for one incident, or {@code null} if unknown
     * @return the incident's severity band. If exactly one of the two is {@code null}, the
     *         known one alone is used as the total (the same "sum what's known" convention
     *         standard SQL {@code SUM()} already applies at the aggregate level elsewhere in
     *         this codebase) - only {@code both} being {@code null} yields {@link SeverityBand#UNKNOWN}.
     */
    public static SeverityBand classify(Integer nkill, Integer nwound) {
        if (nkill == null && nwound == null) {
            return SeverityBand.UNKNOWN;
        }
        int total = (nkill == null ? 0 : nkill) + (nwound == null ? 0 : nwound);
        if (total <= LOW_MAX) {
            return SeverityBand.LOW;
        }
        if (total <= MODERATE_MAX) {
            return SeverityBand.MODERATE;
        }
        if (total <= HIGH_MAX) {
            return SeverityBand.HIGH;
        }
        return SeverityBand.CATASTROPHIC;
    }
}

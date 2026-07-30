package com.gtd.platform.shared.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * CAC-AN-002 (CLAUDE.md section 9): the shared ranking widget component's
 * response shape (e.g. the Executive Workspace's top-countries panel).
 * Shape only, per Phase 2 scope - populated in later phases.
 *
 * @param id      a stable identifier for the ranking widget
 * @param title   the widget's display title
 * @param entries the ranked entries, in rank order
 */
public record RankingResponse(String id, String title, List<RankingEntry> entries) {

    /**
     * @param rank         1-based rank position
     * @param label        the ranked entity's display name (e.g. a country name)
     * @param value        the value it's ranked by
     * @param shareOfTotal this entry's value as a fraction of the total across all entries (0-1), or {@code null} if not applicable
     * @param entityId     the ranked entity's surrogate id (e.g. a {@code dim_country} id) - added in Phase 5 so a
     *                     ranking row can be the target of a drill-down/comparison action, not just displayed
     */
    public record RankingEntry(int rank, String label, BigDecimal value, BigDecimal shareOfTotal, Long entityId) {
    }
}

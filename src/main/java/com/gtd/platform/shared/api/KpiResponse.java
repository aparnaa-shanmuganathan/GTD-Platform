package com.gtd.platform.shared.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * CAC-AN-001 (CLAUDE.md section 9): the shared KPI card component's response
 * shape. Shape only, per Phase 2 scope - populated by SVC-AGG/SVC-TREND in
 * later phases, starting with the Executive Workspace (Phase 4).
 *
 * @param id         a stable identifier for the KPI card (e.g. {@code "kpiIncidents"})
 * @param label      the human-readable KPI name (e.g. {@code "Total incidents"})
 * @param savId      the Shared Analytical Vocabulary id it implements (e.g. {@code "SAV-001"})
 * @param value      the KPI's current value
 * @param unit       a display unit/suffix (e.g. {@code "%"}), or {@code null} for a bare count
 * @param trend      the period-over-period trend, or {@code null} if not applicable
 * @param sparkline  an optional short trend series for a sparkline chart; may be {@code null}
 */
public record KpiResponse(
        String id,
        String label,
        String savId,
        BigDecimal value,
        String unit,
        Trend trend,
        List<BigDecimal> sparkline
) {

    public enum Direction {
        UP,
        DOWN,
        FLAT
    }

    /**
     * @param direction  whether the value moved up/down/flat versus the comparison period
     * @param deltaLabel a human-readable description of the comparison (e.g. {@code "+11.4% vs prior 5yr avg"})
     * @param deltaValue the raw delta value backing {@code deltaLabel}
     */
    public record Trend(Direction direction, String deltaLabel, BigDecimal deltaValue) {
    }
}

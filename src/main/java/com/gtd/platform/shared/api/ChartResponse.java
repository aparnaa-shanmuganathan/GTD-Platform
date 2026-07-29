package com.gtd.platform.shared.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * CAC-AN-003 (CLAUDE.md section 9): the shared chart component's response
 * shape, covering the trend-line and category-breakdown charts referenced
 * across workspaces (rendered with Apache ECharts per CLAUDE.md section 2).
 * Shape only, per Phase 2 scope - populated in later phases.
 *
 * @param id         a stable identifier for the chart
 * @param title      the chart's display title
 * @param type       how the series should be rendered
 * @param categories the x-axis/category labels (e.g. years, or attack type names)
 * @param series     one or more data series, each aligned positionally with {@code categories}
 */
public record ChartResponse(String id, String title, ChartType type, List<String> categories, List<ChartSeries> series) {

    public enum ChartType {
        LINE,
        BAR,
        PIE
    }

    /**
     * @param name the series' display name (shown in the legend)
     * @param data values aligned positionally with the enclosing {@link ChartResponse#categories()}
     */
    public record ChartSeries(String name, List<BigDecimal> data) {
    }
}

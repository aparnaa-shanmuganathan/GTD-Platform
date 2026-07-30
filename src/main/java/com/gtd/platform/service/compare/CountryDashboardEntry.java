package com.gtd.platform.service.compare;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.KpiResponse;
import java.util.List;

/**
 * SVC-COMPARE's single computation unit: one country's KPIs (SVC-AGG) and
 * incidents-by-year trend (SVC-TREND), scoped to that country alone by the
 * rest of the active filter. {@link CompareService#compare} returns 2-4 of
 * these side by side; {@link CompareService#drilldown} returns exactly one -
 * both endpoints share this same shape so the frontend has one rendering
 * path for "a country's dashboard slice," not two.
 *
 * @param countryId   the {@code dim_country} surrogate id
 * @param countryName the country's display name
 * @param kpis        SAV-001..006, scoped to this country (CAC-AN-001 per entry)
 * @param trendChart  the incidents-by-year trend (CAC-AN-003), scoped to this country
 */
public record CountryDashboardEntry(
        Long countryId,
        String countryName,
        List<KpiResponse> kpis,
        ChartResponse trendChart
) {
}

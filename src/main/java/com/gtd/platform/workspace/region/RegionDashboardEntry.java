package com.gtd.platform.workspace.region;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.api.RankingResponse;
import java.util.List;

/**
 * Region Workspace's drill-down shape (Phase 8): one region's KPIs
 * (SVC-AGG), incidents-by-year trend (SVC-TREND), and member-country
 * breakdown - all reused unmodified from Executive/Country's shared
 * components, scoped to a single region by the controller before calling
 * them (the same {@code scopedToCountry}-style filter copy {@code
 * CompareService} already established for Country Workspace).
 *
 * <p>Lives in {@code workspace.region}, not a dedicated {@code service.*}
 * package: unlike {@code CountryDashboardEntry} (owned by {@code
 * service.compare} because SVC-COMPARE is a real, separately-instructed
 * service), Region Workspace has no genuinely new computation of its own -
 * this type is pure orchestration output, and no other workspace needs it.
 *
 * @param regionId             the {@code dim_region} surrogate id
 * @param regionName           the region's display name
 * @param kpis                 SAV-001..006, scoped to this region (CAC-AN-001 per entry)
 * @param trendChart           the incidents-by-year trend (CAC-AN-003), scoped to this region
 * @param memberCountryRanking countries within this region ranked by incident count (CAC-AN-002) -
 *                             "member-country breakdown" for free, since scoping the ranking
 *                             query's own region filter to this one region already returns only
 *                             its member countries
 */
public record RegionDashboardEntry(
        Long regionId,
        String regionName,
        List<KpiResponse> kpis,
        ChartResponse trendChart,
        RankingResponse memberCountryRanking
) {
}

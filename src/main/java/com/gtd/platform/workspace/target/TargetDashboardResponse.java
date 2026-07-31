package com.gtd.platform.workspace.target;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.api.RankingResponse;
import java.util.List;

/**
 * Target Workspace's single-view dashboard shape (Phase 8) - the simplest of the six new
 * workspaces: "target-category analysis across incidents" needs nothing beyond entirely
 * reused shared components (no genuinely new computation at all, unlike every other Phase 8
 * workspace), composed the same one-combined-response way {@code AttackDashboardResponse}/
 * {@code WeaponDashboardResponse} already established.
 *
 * @param kpis        SAV-001..006 (CAC-AN-001 per entry), reused unmodified
 * @param ranking     target types ranked by incident count (CAC-AN-002), AWS-UNK-001 excludes the seeded is_unknown row
 * @param trendChart  incidents-by-year trend (CAC-AN-003), reused unmodified from SVC-TREND
 */
public record TargetDashboardResponse(
        List<KpiResponse> kpis,
        RankingResponse ranking,
        ChartResponse trendChart
) {
}

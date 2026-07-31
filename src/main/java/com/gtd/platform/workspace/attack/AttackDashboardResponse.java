package com.gtd.platform.workspace.attack;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.api.RankingResponse;
import java.util.List;

/**
 * Attack Workspace's single-view dashboard shape (Phase 8) - no drill-down/compare, the same
 * "one combined response" pattern {@code ExecutiveDashboardResponse} established, composed
 * entirely from existing shared components plus one genuinely new piece.
 *
 * @param kpis             SAV-001..006 (CAC-AN-001 per entry), reused unmodified
 * @param ranking          attack types ranked by incident count (CAC-AN-002), AWS-UNK-001 excludes the seeded is_unknown row
 * @param prevalenceChart  attack-type prevalence over time (CAC-AN-003) - one line per non-unknown attack type; the one genuinely new piece this workspace needed (SVC-TREND)
 * @param successRateChart success/failure trend over time (CAC-AN-003) - reused unmodified from SVC-TREND's existing success-rate series
 */
public record AttackDashboardResponse(
        List<KpiResponse> kpis,
        RankingResponse ranking,
        ChartResponse prevalenceChart,
        ChartResponse successRateChart
) {
}

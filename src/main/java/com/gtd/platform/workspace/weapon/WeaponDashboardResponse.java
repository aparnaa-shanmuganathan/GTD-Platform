package com.gtd.platform.workspace.weapon;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.api.RankingResponse;
import java.util.List;

/**
 * Weapon Workspace's single-view dashboard shape (Phase 8) - no drill-down/compare, the same
 * one-combined-response pattern {@code AttackDashboardResponse} established.
 *
 * @param kpis            SAV-001..006 (CAC-AN-001 per entry), reused unmodified
 * @param ranking         weapon types ranked by incident count (CAC-AN-002), AWS-UNK-001 excludes the seeded is_unknown row
 * @param severityChart   average fatalities/injuries per incident by weapon type (CAC-AN-003) - the one genuinely new piece this workspace needed
 */
public record WeaponDashboardResponse(
        List<KpiResponse> kpis,
        RankingResponse ranking,
        ChartResponse severityChart
) {
}

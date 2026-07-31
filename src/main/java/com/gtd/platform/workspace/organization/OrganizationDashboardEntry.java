package com.gtd.platform.workspace.organization;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.KpiResponse;
import java.util.List;

/**
 * Organization Workspace's drill-down shape (Phase 8): one organization's
 * KPIs (SVC-AGG) and incidents-by-year trend (SVC-TREND), scoped to a
 * single organization by the controller before calling those services -
 * the same orchestration-only shape {@code RegionDashboardEntry} already
 * established for Region Workspace.
 *
 * <p>"Geographic reach" needs no dedicated field: {@code kpis} already
 * includes SAV-005 ("Countries affected") via {@code
 * KpiAggregationService.computeExecutiveKpis} - scoped to one
 * organization's incidents, that KPI already answers "how many countries
 * has this organization operated in."
 *
 * @param organizationId   the {@code dim_organization} surrogate id
 * @param organizationName the organization's display name
 * @param kpis             SAV-001..006, scoped to this organization (CAC-AN-001 per entry)
 * @param trendChart       the incidents-by-year trend (CAC-AN-003), scoped to this organization
 */
public record OrganizationDashboardEntry(
        Long organizationId,
        String organizationName,
        List<KpiResponse> kpis,
        ChartResponse trendChart
) {
}

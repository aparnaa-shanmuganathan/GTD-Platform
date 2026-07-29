package com.gtd.platform.shared.db;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds a parameterized SQL {@code WHERE} clause (and matching bind
 * parameters, in order) for {@code fact_incident} from the canonical filter
 * DTO (CAC-UI-003, {@link AnalyticalFilterRequest}). Shared by SVC-AGG and
 * SVC-TREND (both query {@code fact_incident} directly - CLAUDE.md section
 * 12) so the filter-to-SQL translation has exactly one owner, the same
 * principle CLAUDE.md section 6 applies to SAV computations. Lives in
 * {@code shared.db} (not either service package) so neither service depends
 * on the other for it.
 *
 * <p>{@code startDate}/{@code endDate} are applied at YEAR granularity via
 * {@code event_year}: {@code fact_incident.event_month}/{@code event_day}
 * are frequently {@code null} (GTD's "unknown month/day" convention, Phase 3),
 * so filtering at day/month precision would silently and incorrectly exclude
 * legitimate rows. Year is also the trend chart's native granularity, so
 * this doesn't lose anything the rest of the workspace exposes.
 */
public final class FactIncidentFilterSqlBuilder {

    private FactIncidentFilterSqlBuilder() {
    }

    public static BuiltFilter build(AnalyticalFilterRequest filter) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (filter.getStartDate() != null) {
            conditions.add("event_year >= ?");
            params.add(filter.getStartDate().getYear());
        }
        if (filter.getEndDate() != null) {
            conditions.add("event_year <= ?");
            params.add(filter.getEndDate().getYear());
        }
        addInClause(conditions, params, "region_id", filter.getRegionIds());
        addInClause(conditions, params, "country_id", filter.getCountryIds());
        addInClause(conditions, params, "attack_type_id", filter.getAttackTypeIds());
        addInClause(conditions, params, "weapon_type_id", filter.getWeaponTypeIds());
        addInClause(conditions, params, "target_type_id", filter.getTargetTypeIds());
        addInClause(conditions, params, "organization_id", filter.getOrganizationIds());

        String whereSql = conditions.isEmpty() ? "1=1" : String.join(" AND ", conditions);
        return new BuiltFilter(whereSql, params);
    }

    private static void addInClause(List<String> conditions, List<Object> params, String column, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        conditions.add(column + " IN (" + placeholders + ")");
        params.addAll(ids);
    }

    /**
     * @param whereClauseSql a valid SQL boolean expression (never empty - {@code "1=1"} when no filter applies), safe to inline after {@code WHERE}
     * @param params         bind parameters in the same order as the {@code ?} placeholders in {@code whereClauseSql}
     */
    public record BuiltFilter(String whereClauseSql, List<Object> params) {
    }
}

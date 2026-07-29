package com.gtd.platform.shared.db;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the date-range half of the canonical filter DTO (CAC-UI-003) as a
 * SQL {@code WHERE} fragment against an arbitrary year column - used by
 * queries against the {@code mv_*_year_agg} materialized views, which (unlike
 * {@code fact_incident}, see {@link FactIncidentFilterSqlBuilder}) only ever
 * need year filtering since each view already carries just one other
 * dimension. Shared by {@code service.agg.RankingAndBreakdownService} and
 * {@code service.insight}'s insight generation so this translation has
 * exactly one owner.
 */
public final class YearRangeSqlHelper {

    private YearRangeSqlHelper() {
    }

    /**
     * @param filter     the active filter; only {@code startDate}/{@code endDate} are consulted
     * @param params     bind parameters are appended here, in the same order as the {@code ?} placeholders produced
     * @param yearColumn the (possibly table-qualified) year column to filter, e.g. {@code "mv.event_year"}
     * @return a valid SQL boolean expression (never empty - {@code "1=1"} when no date filter applies)
     */
    public static String whereClause(AnalyticalFilterRequest filter, List<Object> params, String yearColumn) {
        List<String> conditions = new ArrayList<>();
        if (filter.getStartDate() != null) {
            conditions.add(yearColumn + " >= ?");
            params.add(filter.getStartDate().getYear());
        }
        if (filter.getEndDate() != null) {
            conditions.add(yearColumn + " <= ?");
            params.add(filter.getEndDate().getYear());
        }
        return conditions.isEmpty() ? "1=1" : String.join(" AND ", conditions);
    }
}

package com.gtd.platform.service.compare;

import com.gtd.platform.service.agg.KpiAggregationService;
import com.gtd.platform.service.trend.TrendService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.db.DimCountry;
import com.gtd.platform.shared.db.DimCountryRepository;
import com.gtd.platform.shared.exception.ApiException;
import com.gtd.platform.shared.exception.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * SVC-COMPARE (CLAUDE.md section 4): side-by-side comparison of up to 4
 * countries, plus the single-country time-trend drill-down as a degenerate
 * 1-country case of the exact same computation. Implemented as its own
 * service - not folded into SVC-AGG - per the user's explicit instruction
 * for Phase 5, but it deliberately reuses {@link KpiAggregationService}
 * (SVC-AGG) and {@link TrendService} (SVC-TREND) for the actual numbers
 * rather than recomputing SAV-001..006 or the yearly trend series itself:
 * "distinct analytical service" means a distinct orchestration/scoping
 * concern (per-country isolation of the canonical filter), not a second
 * computation owner for figures SVC-AGG/SVC-TREND already own.
 *
 * <p>{@link #compare} and {@link #drilldown} both funnel through
 * {@link #countryDashboard}, the one place that scopes the incoming
 * canonical filter (CAC-UI-003) to a single country and calls SVC-AGG/
 * SVC-TREND against that scoped filter - see CLAUDE.md section 12 for why
 * drill-down is not a separate implementation.
 */
@Service
public class CompareService {

    private static final int MIN_COMPARE_COUNTRIES = 2;
    private static final int MAX_COMPARE_COUNTRIES = 4;

    private final KpiAggregationService kpiAggregationService;
    private final TrendService trendService;
    private final DimCountryRepository countryRepository;

    public CompareService(KpiAggregationService kpiAggregationService, TrendService trendService,
            DimCountryRepository countryRepository) {
        this.kpiAggregationService = kpiAggregationService;
        this.trendService = trendService;
        this.countryRepository = countryRepository;
    }

    /**
     * @param filter the canonical filter (CAC-UI-003); {@code countryIds} must contain 2-4 ids -
     *               these are the countries being compared, not a combining filter
     * @return one {@link CountryDashboardEntry} per requested country, in the same order requested
     */
    public List<CountryDashboardEntry> compare(AnalyticalFilterRequest filter) {
        List<Long> countryIds = filter.getCountryIds();
        if (countryIds == null || countryIds.size() < MIN_COMPARE_COUNTRIES || countryIds.size() > MAX_COMPARE_COUNTRIES) {
            throw new ApiException(ErrorCode.ERR_COMPARE_001);
        }
        return countryIds.stream().map(id -> countryDashboard(filter, id)).toList();
    }

    /**
     * @param filter the canonical filter (CAC-UI-003); {@code countryIds} must contain exactly 1 id -
     *               the country being drilled into
     * @return that single country's {@link CountryDashboardEntry}
     */
    public CountryDashboardEntry drilldown(AnalyticalFilterRequest filter) {
        List<Long> countryIds = filter.getCountryIds();
        if (countryIds == null || countryIds.size() != 1) {
            throw new ApiException(ErrorCode.ERR_COMPARE_002);
        }
        return countryDashboard(filter, countryIds.get(0));
    }

    private CountryDashboardEntry countryDashboard(AnalyticalFilterRequest filter, long countryId) {
        DimCountry country = countryRepository.findById(countryId)
                .orElseThrow(() -> new ApiException(ErrorCode.ERR_COMPARE_003, "No country with id " + countryId));

        AnalyticalFilterRequest scoped = scopedToCountry(filter, countryId);
        return new CountryDashboardEntry(
                countryId,
                country.getName(),
                kpiAggregationService.computeExecutiveKpis(scoped),
                trendService.computeIncidentsByYearTrend(scoped));
    }

    /**
     * Copies every filter dimension except {@code countryIds} from {@code base} (date range, region,
     * attack/weapon/target type, organization all still apply as shared cross-cutting filters), then
     * pins {@code countryIds} to exactly the one country being scoped - so SVC-AGG/SVC-TREND compute
     * that country's slice of the same filtered dataset every other compared/drilled-into country uses.
     */
    private AnalyticalFilterRequest scopedToCountry(AnalyticalFilterRequest base, long countryId) {
        AnalyticalFilterRequest scoped = new AnalyticalFilterRequest();
        scoped.setStartDate(base.getStartDate());
        scoped.setEndDate(base.getEndDate());
        scoped.setRegionIds(base.getRegionIds());
        scoped.setAttackTypeIds(base.getAttackTypeIds());
        scoped.setWeaponTypeIds(base.getWeaponTypeIds());
        scoped.setTargetTypeIds(base.getTargetTypeIds());
        scoped.setOrganizationIds(base.getOrganizationIds());
        scoped.setCountryIds(List.of(countryId));
        return scoped;
    }
}

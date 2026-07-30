package com.gtd.platform.service.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.DimAttackType;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimCountry;
import com.gtd.platform.shared.db.DimCountryRepository;
import com.gtd.platform.shared.db.DimOrganization;
import com.gtd.platform.shared.db.DimOrganizationRepository;
import com.gtd.platform.shared.db.DimTargetType;
import com.gtd.platform.shared.db.DimTargetTypeRepository;
import com.gtd.platform.shared.db.DimWeaponType;
import com.gtd.platform.shared.db.DimWeaponTypeRepository;
import com.gtd.platform.shared.db.FactIncident;
import com.gtd.platform.shared.db.FactIncidentRepository;
import com.gtd.platform.shared.db.ImportBatchRepository;
import com.gtd.platform.shared.exception.ApiException;
import com.gtd.platform.shared.exception.ErrorCode;
import com.gtd.platform.testsupport.FactIncidentTestFixtures;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies SVC-COMPARE's per-country scoping against hand-computed values
 * from known {@code fact_incident} rows - not just "a number comes back" -
 * plus its 2-4/exactly-1 validation and not-found handling. Uses a
 * distinctive future year (2091) so these assertions are unaffected by
 * whatever other test classes have inserted into the same shared test
 * database (no transactional rollback between test classes, same
 * convention {@code KpiAggregationServiceTest}/{@code TrendServiceTest} use).
 */
@SpringBootTest
class CompareServiceTest extends PostgresIntegrationTestBase {

    @Autowired
    private CompareService compareService;
    @Autowired
    private FactIncidentRepository factIncidentRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private DatasetVersionRepository datasetVersionRepository;
    @Autowired
    private DimCountryRepository countryRepository;
    @Autowired
    private DimAttackTypeRepository attackTypeRepository;
    @Autowired
    private DimWeaponTypeRepository weaponTypeRepository;
    @Autowired
    private DimTargetTypeRepository targetTypeRepository;
    @Autowired
    private DimOrganizationRepository organizationRepository;

    private DatasetVersion datasetVersion;
    private DimCountry us;
    private DimCountry canada;
    private DimCountry mexico;
    private DimCountry colombia;
    private DimAttackType attackType;
    private DimWeaponType weaponType;
    private DimTargetType targetType;
    private DimOrganization org;

    @BeforeEach
    void setUp() {
        datasetVersion = FactIncidentTestFixtures.createDatasetVersion(importBatchRepository, datasetVersionRepository);
        us = countryRepository.findByName("United States").orElseThrow();
        canada = countryRepository.findByName("Canada").orElseThrow();
        mexico = countryRepository.findByName("Mexico").orElseThrow();
        colombia = countryRepository.findByName("Colombia").orElseThrow();
        attackType = attackTypeRepository.findByName("Bombing/Explosion").orElseThrow();
        weaponType = weaponTypeRepository.findByName("Explosives").orElseThrow();
        targetType = targetTypeRepository.findByName("Business").orElseThrow();
        org = organizationRepository.findByName("Taliban").orElseThrow();
    }

    /**
     * Each test that needs real rows seeds its own distinct year (rather than sharing one
     * fixture year across every test method in this class): {@code @BeforeEach} runs before
     * every method with no rollback between them, so rows inserted for a shared year would
     * silently accumulate across methods within this same test run.
     */
    private void seedCountryRows(int year) {
        // US: 2 rows -> incidents=2, fatalities=2+1=3, injuries=1+0=1, success=1/2=50%
        saveIncident(us, attackType, weaponType, targetType, org, year, 2, 1, true);
        saveIncident(us, attackType, weaponType, targetType, org, year, 1, 0, false);
        // Canada: 1 row -> incidents=1, fatalities=5, injuries=2, success=100%
        saveIncident(canada, attackType, weaponType, targetType, org, year, 5, 2, true);
        // Mexico: 1 row with NULL nkill -> incidents=1, fatalities=0 (NULL sum, never coalesced elsewhere,
        // but KpiAggregationService.orZero surfaces it as 0 here), injuries=3, success=0%
        saveIncident(mexico, attackType, weaponType, targetType, org, year, null, 3, false);
        // Colombia: no rows at all -> incidents=0, fatalities=0, injuries=0, success=0%
    }

    @Test
    void compareScopesEachCountrysKpisAndTrendIndependently() {
        int year = 2091;
        seedCountryRows(year);
        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setStartDate(LocalDate.of(year, 1, 1));
        filter.setEndDate(LocalDate.of(year, 12, 31));
        filter.setCountryIds(List.of(us.getId(), canada.getId(), mexico.getId(), colombia.getId()));

        List<CountryDashboardEntry> entries = compareService.compare(filter);

        assertThat(entries).hasSize(4);
        // Same order as requested.
        assertThat(entries.stream().map(CountryDashboardEntry::countryId).toList())
                .containsExactly(us.getId(), canada.getId(), mexico.getId(), colombia.getId());

        Map<Long, CountryDashboardEntry> byId = entries.stream()
                .collect(Collectors.toMap(CountryDashboardEntry::countryId, e -> e));

        assertThat(byId.get(us.getId()).countryName()).isEqualTo("United States");
        assertThat(kpiValue(byId.get(us.getId()), "kpiIncidents")).isEqualByComparingTo("2");
        assertThat(kpiValue(byId.get(us.getId()), "kpiFatalities")).isEqualByComparingTo("3");
        assertThat(kpiValue(byId.get(us.getId()), "kpiInjuries")).isEqualByComparingTo("1");
        assertThat(kpiValue(byId.get(us.getId()), "kpiSuccess")).isEqualByComparingTo("50.00");

        assertThat(kpiValue(byId.get(canada.getId()), "kpiIncidents")).isEqualByComparingTo("1");
        assertThat(kpiValue(byId.get(canada.getId()), "kpiFatalities")).isEqualByComparingTo("5");
        assertThat(kpiValue(byId.get(canada.getId()), "kpiSuccess")).isEqualByComparingTo("100.00");

        // NULL nkill is never coalesced to zero at the row level (sections 7/8), but SUM() of a
        // single-NULL-row group has nothing else to sum - KpiAggregationService.orZero surfaces
        // that as a displayable 0, not a missing value, for this one-row-only scoped query.
        assertThat(kpiValue(byId.get(mexico.getId()), "kpiFatalities")).isEqualByComparingTo("0");
        assertThat(kpiValue(byId.get(mexico.getId()), "kpiInjuries")).isEqualByComparingTo("3");

        assertThat(kpiValue(byId.get(colombia.getId()), "kpiIncidents")).isEqualByComparingTo("0");
        assertThat(kpiValue(byId.get(colombia.getId()), "kpiSuccess")).isEqualByComparingTo("0");

        // Each country's trend chart only reflects that country's own rows.
        assertThat(byId.get(us.getId()).trendChart().categories()).containsExactly(String.valueOf(year));
        assertThat(byId.get(us.getId()).trendChart().series().get(0).data().get(0)).isEqualByComparingTo("2");
        assertThat(byId.get(canada.getId()).trendChart().series().get(0).data().get(0)).isEqualByComparingTo("1");
        assertThat(byId.get(colombia.getId()).trendChart().categories()).isEmpty();
    }

    @Test
    void compareRejectsFewerThanTwoCountries() {
        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setCountryIds(List.of(us.getId()));

        assertThatThrownBy(() -> compareService.compare(filter))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.ERR_COMPARE_001);
    }

    @Test
    void compareRejectsMoreThanFourCountries() {
        DimCountry brazil = countryRepository.findByName("Brazil").orElseThrow();
        DimCountry peru = countryRepository.findByName("Peru").orElseThrow();
        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setCountryIds(List.of(us.getId(), canada.getId(), mexico.getId(), colombia.getId(),
                brazil.getId(), peru.getId()));

        assertThatThrownBy(() -> compareService.compare(filter))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.ERR_COMPARE_001);
    }

    @Test
    void drilldownReturnsSingleCountrysDashboard() {
        int year = 2092;
        seedCountryRows(year);
        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setStartDate(LocalDate.of(year, 1, 1));
        filter.setEndDate(LocalDate.of(year, 12, 31));
        filter.setCountryIds(List.of(us.getId()));

        CountryDashboardEntry entry = compareService.drilldown(filter);

        assertThat(entry.countryId()).isEqualTo(us.getId());
        assertThat(entry.countryName()).isEqualTo("United States");
        assertThat(kpiValue(entry, "kpiIncidents")).isEqualByComparingTo("2");
        assertThat(entry.trendChart().categories()).containsExactly(String.valueOf(year));
    }

    @Test
    void drilldownRejectsZeroCountries() {
        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setCountryIds(List.of());

        assertThatThrownBy(() -> compareService.drilldown(filter))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.ERR_COMPARE_002);
    }

    @Test
    void drilldownRejectsMoreThanOneCountry() {
        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setCountryIds(List.of(us.getId(), canada.getId()));

        assertThatThrownBy(() -> compareService.drilldown(filter))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.ERR_COMPARE_002);
    }

    @Test
    void nonexistentCountryIdIsRejectedAsNotFound() {
        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setCountryIds(List.of(999_999_999L));

        assertThatThrownBy(() -> compareService.drilldown(filter))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.ERR_COMPARE_003);
    }

    private static java.math.BigDecimal kpiValue(CountryDashboardEntry entry, String kpiId) {
        return entry.kpis().stream()
                .filter(k -> k.id().equals(kpiId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No KPI with id " + kpiId))
                .value();
    }

    private void saveIncident(DimCountry country, DimAttackType attackType, DimWeaponType weaponType,
            DimTargetType targetType, DimOrganization organization, int year, Integer nkill, Integer nwound,
            Boolean success) {
        FactIncident incident = new FactIncident();
        incident.setEventId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        incident.setDatasetVersion(datasetVersion);
        incident.setCountry(country);
        incident.setRegion(country.getRegion());
        incident.setAttackType(attackType);
        incident.setWeaponType(weaponType);
        incident.setTargetType(targetType);
        incident.setOrganization(organization);
        incident.setEventYear(year);
        incident.setNkill(nkill);
        incident.setNwound(nwound);
        incident.setSuccess(success);
        incident.setCreatedAt(Instant.now());
        factIncidentRepository.save(incident);
    }
}

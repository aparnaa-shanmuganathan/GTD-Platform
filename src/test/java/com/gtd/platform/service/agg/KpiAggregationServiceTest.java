package com.gtd.platform.service.agg;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimCountryRepository;
import com.gtd.platform.shared.db.DimOrganizationRepository;
import com.gtd.platform.shared.db.DimTargetTypeRepository;
import com.gtd.platform.shared.db.DimWeaponTypeRepository;
import com.gtd.platform.shared.db.FactIncident;
import com.gtd.platform.shared.db.FactIncidentRepository;
import com.gtd.platform.shared.db.ImportBatchRepository;
import com.gtd.platform.testsupport.FactIncidentTestFixtures;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies SVC-AGG's SAV-001..006 formulas against hand-computed values from
 * known {@code fact_incident} rows - not just "a number comes back".
 */
@SpringBootTest
class KpiAggregationServiceTest extends PostgresIntegrationTestBase {

    @Autowired
    private KpiAggregationService kpiAggregationService;
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

    @BeforeEach
    void setUp() {
        datasetVersion = FactIncidentTestFixtures.createDatasetVersion(importBatchRepository, datasetVersionRepository);
    }

    /**
     * Five rows with deliberately incomplete casualty data and two rows
     * attributed to the seeded is_unknown organization, so the test proves:
     * SUM() skips nulls rather than coalescing to zero (sections 7/8),
     * success rate excludes no incident from the denominator, distinct
     * countries counts every named country, and active organizations
     * excludes the is_unknown row (AWS-UNK-001) - all in one pass.
     *
     * <p>Uses a distinctive future year range (2085-2087) plus an explicit
     * date-range filter matching it, rather than an unfiltered query: other
     * test classes share this database with no rollback, and an unfiltered
     * query would otherwise pick up their rows too, breaking these exact
     * hand-computed assertions.
     */
    @Test
    void computesAllSixKpisAgainstKnownData() {
        var us = countryRepository.findByName("United States").orElseThrow();
        var canada = countryRepository.findByName("Canada").orElseThrow();
        var attackType = attackTypeRepository.findByName("Bombing/Explosion").orElseThrow();
        var weaponType = weaponTypeRepository.findByName("Explosives").orElseThrow();
        var targetType = targetTypeRepository.findByName("Business").orElseThrow();
        var taliban = organizationRepository.findByName("Taliban").orElseThrow();
        var alShabaab = organizationRepository.findByName("Al-Shabaab").orElseThrow();
        var unknownOrg = organizationRepository.findByUnknownTrue().orElseThrow();

        // Row 1: US, 2085, nkill=2, nwound=3, success, Taliban
        saveIncident(us, attackType, weaponType, targetType, taliban, 2085, 2, 3, true);
        // Row 2: US, 2086, nkill=1, nwound=0, NOT success, Unknown org
        saveIncident(us, attackType, weaponType, targetType, unknownOrg, 2086, 1, 0, false);
        // Row 3: Canada, 2086, nkill=NULL, nwound=5, success, Taliban
        saveIncident(canada, attackType, weaponType, targetType, taliban, 2086, null, 5, true);
        // Row 4: Canada, 2087, nkill=0, nwound=0, success, Al-Shabaab
        saveIncident(canada, attackType, weaponType, targetType, alShabaab, 2087, 0, 0, true);
        // Row 5: US, 2087, nkill=3, nwound=NULL, success=NULL, Unknown org
        saveIncident(us, attackType, weaponType, targetType, unknownOrg, 2087, 3, null, null);

        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setStartDate(java.time.LocalDate.of(2085, 1, 1));
        filter.setEndDate(java.time.LocalDate.of(2087, 12, 31));

        List<KpiResponse> kpis = kpiAggregationService.computeExecutiveKpis(filter);
        Map<String, KpiResponse> byId = kpis.stream().collect(java.util.stream.Collectors.toMap(KpiResponse::id, k -> k));

        assertThat(byId.get("kpiIncidents").value()).isEqualByComparingTo("5");
        // SUM(nkill) skipping the one NULL row: 2+1+0+3 = 6
        assertThat(byId.get("kpiFatalities").value()).isEqualByComparingTo("6");
        // SUM(nwound) skipping the one NULL row: 3+0+5+0 = 8
        assertThat(byId.get("kpiInjuries").value()).isEqualByComparingTo("8");
        // 3 of 5 rows have is_success = TRUE (row 5's NULL success doesn't count): 60.00%
        assertThat(byId.get("kpiSuccess").value()).isEqualByComparingTo("60.00");
        assertThat(byId.get("kpiSuccess").unit()).isEqualTo("%");
        // Distinct countries: United States, Canada
        assertThat(byId.get("kpiCountries").value()).isEqualByComparingTo("2");
        // Distinct orgs EXCLUDING the is_unknown row (AWS-UNK-001): Taliban, Al-Shabaab
        assertThat(byId.get("kpiOrgs").value()).isEqualByComparingTo("2");

        // Multi-year data (2020/2021/2022) present -> a growth-rate delta must be computable.
        assertThat(byId.get("kpiIncidents").trend()).isNotNull();
        // Distinct-count KPIs deliberately carry no trend (see KpiAggregationService Javadoc).
        assertThat(byId.get("kpiCountries").trend()).isNull();
        assertThat(byId.get("kpiOrgs").trend()).isNull();
    }

    /**
     * Uses a date range (1800-1801) no real or test-fixture data can ever
     * fall within, rather than assuming the whole table is empty - other
     * test classes share this same database with no transactional
     * rollback, so a truly "empty table" assumption would be order-dependent.
     */
    @Test
    void filterMatchingNoRowsYieldsZeroValuedKpisNotErrors() {
        AnalyticalFilterRequest impossibleRange = new AnalyticalFilterRequest();
        impossibleRange.setStartDate(java.time.LocalDate.of(1800, 1, 1));
        impossibleRange.setEndDate(java.time.LocalDate.of(1801, 1, 1));

        List<KpiResponse> kpis = kpiAggregationService.computeExecutiveKpis(impossibleRange);
        Map<String, KpiResponse> byId = kpis.stream().collect(java.util.stream.Collectors.toMap(KpiResponse::id, k -> k));

        assertThat(byId.get("kpiIncidents").value()).isEqualByComparingTo("0");
        assertThat(byId.get("kpiFatalities").value()).isEqualByComparingTo("0");
        assertThat(byId.get("kpiSuccess").value()).isEqualByComparingTo("0");
        assertThat(byId.get("kpiIncidents").trend()).isNull();
    }

    private void saveIncident(com.gtd.platform.shared.db.DimCountry country,
            com.gtd.platform.shared.db.DimAttackType attackType, com.gtd.platform.shared.db.DimWeaponType weaponType,
            com.gtd.platform.shared.db.DimTargetType targetType, com.gtd.platform.shared.db.DimOrganization organization,
            int year, Integer nkill, Integer nwound, Boolean success) {
        FactIncident incident = new FactIncident();
        incident.setEventId(randomEventId());
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

    private static String randomEventId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

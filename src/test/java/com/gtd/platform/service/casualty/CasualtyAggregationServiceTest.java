package com.gtd.platform.service.casualty;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ChartResponse;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies {@link CasualtyAggregationService}'s Total Casualties / average-per-incident KPIs
 * and severity-band distribution against hand-computed values from known {@code fact_incident}
 * rows - not just "a number comes back" - the same rigor {@code KpiAggregationServiceTest}/
 * {@code TrendServiceTest} hold SVC-AGG/SVC-TREND to. Uses a distinctive future year range
 * (2140-2147, reserved for this service - CLAUDE.md section 12's Phase 8 decisions) plus an
 * explicit date-range filter so this test's grouping is unaffected by other test classes'
 * shared-database rows.
 */
@SpringBootTest
class CasualtyAggregationServiceTest extends PostgresIntegrationTestBase {

    @Autowired
    private CasualtyAggregationService casualtyAggregationService;
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
     * Row 1: nkill=2, nwound=3 -> total=5 (MODERATE).
     * Row 2: nkill=NULL, nwound=NULL -> UNKNOWN (never silently LOW).
     * Row 3: nkill=0, nwound=0 -> total=0 (LOW).
     * Row 4: nkill=60, nwound=0 -> total=60 (CATASTROPHIC).
     * Row 5: nkill=NULL, nwound=8 -> total=8, the known side only (MODERATE).
     *
     * <p>Total casualties = SUM(nkill) skipping NULLs (2+0+60=62) + SUM(nwound) skipping NULLs
     * (3+0+0+8=11) = 73. Avg fatalities = AVG(2,0,60) = 62/3 = 20.666... -> 20.67. Avg injuries =
     * AVG(3,0,0,8) = 11/4 = 2.75 exactly.
     */
    @Test
    void computesCasualtyKpisAndSeverityBandsAgainstKnownData() {
        var us = countryRepository.findByName("United States").orElseThrow();
        var canada = countryRepository.findByName("Canada").orElseThrow();
        var attackType = attackTypeRepository.findByName("Bombing/Explosion").orElseThrow();
        var weaponType = weaponTypeRepository.findByName("Explosives").orElseThrow();
        var targetType = targetTypeRepository.findByName("Business").orElseThrow();
        var org = organizationRepository.findByName("Taliban").orElseThrow();

        saveIncident(us, attackType, weaponType, targetType, org, 2, 3);
        saveIncident(us, attackType, weaponType, targetType, org, null, null);
        saveIncident(canada, attackType, weaponType, targetType, org, 0, 0);
        saveIncident(canada, attackType, weaponType, targetType, org, 60, 0);
        saveIncident(us, attackType, weaponType, targetType, org, null, 8);

        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setStartDate(java.time.LocalDate.of(2140, 1, 1));
        filter.setEndDate(java.time.LocalDate.of(2140, 12, 31));

        List<KpiResponse> kpis = casualtyAggregationService.computeCasualtyKpis(filter);
        Map<String, KpiResponse> byId = kpis.stream().collect(Collectors.toMap(KpiResponse::id, k -> k));

        assertThat(byId.get("kpiTotalCasualties").value()).isEqualByComparingTo("73");
        assertThat(byId.get("kpiAvgFatalities").value()).isEqualByComparingTo("20.67");
        assertThat(byId.get("kpiAvgInjuries").value()).isEqualByComparingTo("2.75");

        ChartResponse bandChart = casualtyAggregationService.computeSeverityBandDistribution(filter);
        assertThat(bandChart.id()).isEqualTo("severityBandChart");
        assertThat(bandChart.categories()).containsExactly("LOW", "MODERATE", "HIGH", "CATASTROPHIC", "UNKNOWN");
        List<java.math.BigDecimal> counts = bandChart.series().get(0).data();
        assertThat(counts.get(0)).isEqualByComparingTo("1"); // LOW: row 3
        assertThat(counts.get(1)).isEqualByComparingTo("2"); // MODERATE: rows 1, 5
        assertThat(counts.get(2)).isEqualByComparingTo("0"); // HIGH: none
        assertThat(counts.get(3)).isEqualByComparingTo("1"); // CATASTROPHIC: row 4
        assertThat(counts.get(4)).isEqualByComparingTo("1"); // UNKNOWN: row 2
    }

    /**
     * Uses a date range no real or test-fixture data can ever fall within, rather than assuming
     * the whole table is empty - other test classes share this same database with no
     * transactional rollback.
     */
    @Test
    void filterMatchingNoRowsYieldsZeroValuedKpisNotErrors() {
        AnalyticalFilterRequest impossibleRange = new AnalyticalFilterRequest();
        impossibleRange.setStartDate(java.time.LocalDate.of(1800, 1, 1));
        impossibleRange.setEndDate(java.time.LocalDate.of(1801, 1, 1));

        List<KpiResponse> kpis = casualtyAggregationService.computeCasualtyKpis(impossibleRange);
        Map<String, KpiResponse> byId = kpis.stream().collect(Collectors.toMap(KpiResponse::id, k -> k));

        assertThat(byId.get("kpiTotalCasualties").value()).isEqualByComparingTo("0");
        assertThat(byId.get("kpiAvgFatalities").value()).isEqualByComparingTo("0.00");
        assertThat(byId.get("kpiAvgInjuries").value()).isEqualByComparingTo("0.00");
    }

    private void saveIncident(com.gtd.platform.shared.db.DimCountry country,
            com.gtd.platform.shared.db.DimAttackType attackType, com.gtd.platform.shared.db.DimWeaponType weaponType,
            com.gtd.platform.shared.db.DimTargetType targetType, com.gtd.platform.shared.db.DimOrganization organization,
            Integer nkill, Integer nwound) {
        FactIncident incident = new FactIncident();
        incident.setEventId(randomEventId());
        incident.setDatasetVersion(datasetVersion);
        incident.setCountry(country);
        incident.setRegion(country.getRegion());
        incident.setAttackType(attackType);
        incident.setWeaponType(weaponType);
        incident.setTargetType(targetType);
        incident.setOrganization(organization);
        incident.setEventYear(2140);
        incident.setNkill(nkill);
        incident.setNwound(nwound);
        incident.setSuccess(true);
        incident.setCreatedAt(Instant.now());
        factIncidentRepository.save(incident);
    }

    private static String randomEventId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

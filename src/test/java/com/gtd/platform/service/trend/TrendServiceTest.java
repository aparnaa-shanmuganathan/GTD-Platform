package com.gtd.platform.service.trend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ChartResponse;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.SortedMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies SVC-TREND's per-year series against hand-computed values from
 * known {@code fact_incident} rows, using a distinctive future year range
 * (2093/2094) so this test's grouping is unaffected regardless of what
 * other test classes have inserted into the same shared test database.
 */
@SpringBootTest
class TrendServiceTest extends PostgresIntegrationTestBase {

    @Autowired
    private TrendService trendService;
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

    @Test
    void computesPerYearSeriesAgainstKnownData() {
        var country = countryRepository.findByName("United States").orElseThrow();
        var attackType = attackTypeRepository.findByName("Hijacking").orElseThrow();
        var weaponType = weaponTypeRepository.findByName("Sabotage Equipment").orElseThrow();
        var targetType = targetTypeRepository.findByName("Maritime").orElseThrow();
        var org = organizationRepository.findByName("Irish Republican Army (IRA)").orElseThrow();

        // 2093: two rows (nkill 5 & 3 -> 8; nwound 2 & 1 -> 3; success 1 of 2 -> 50%)
        saveIncident(country, attackType, weaponType, targetType, org, 2093, 5, 2, true);
        saveIncident(country, attackType, weaponType, targetType, org, 2093, 3, 1, false);
        // 2094: two rows (nkill 7 & 1 -> 8; nwound 4 & 0 -> 4; success 2 of 2 -> 100%)
        saveIncident(country, attackType, weaponType, targetType, org, 2094, 7, 4, true);
        saveIncident(country, attackType, weaponType, targetType, org, 2094, 1, 0, true);

        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setAttackTypeIds(java.util.List.of(attackType.getId()));

        SortedMap<Integer, BigDecimal> incidents = trendService.incidentCountByYear(filter);
        assertThat(incidents.get(2093)).isEqualByComparingTo("2");
        assertThat(incidents.get(2094)).isEqualByComparingTo("2");

        SortedMap<Integer, BigDecimal> fatalities = trendService.fatalitiesByYear(filter);
        assertThat(fatalities.get(2093)).isEqualByComparingTo("8");
        assertThat(fatalities.get(2094)).isEqualByComparingTo("8");

        SortedMap<Integer, BigDecimal> injuries = trendService.injuriesByYear(filter);
        assertThat(injuries.get(2093)).isEqualByComparingTo("3");
        assertThat(injuries.get(2094)).isEqualByComparingTo("4");

        SortedMap<Integer, BigDecimal> successRate = trendService.successRatePercentByYear(filter);
        assertThat(successRate.get(2093)).isEqualByComparingTo("50.0");
        assertThat(successRate.get(2094)).isEqualByComparingTo("100.0");

        ChartResponse chart = trendService.computeIncidentsByYearTrend(filter);
        assertThat(chart.categories()).contains("2093", "2094");
        assertThat(chart.series()).hasSize(1);
        assertThat(chart.type()).isEqualTo(ChartResponse.ChartType.LINE);
    }

    private void saveIncident(com.gtd.platform.shared.db.DimCountry country,
            com.gtd.platform.shared.db.DimAttackType attackType, com.gtd.platform.shared.db.DimWeaponType weaponType,
            com.gtd.platform.shared.db.DimTargetType targetType, com.gtd.platform.shared.db.DimOrganization organization,
            int year, Integer nkill, Integer nwound, Boolean success) {
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

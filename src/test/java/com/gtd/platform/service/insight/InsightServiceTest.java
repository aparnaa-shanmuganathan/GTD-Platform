package com.gtd.platform.service.insight;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.InsightsResponse;
import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.DimAttackType;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimCountry;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies INSIGHT actually computes its two narrative items from real data
 * rather than returning hardcoded text: the growth-rate-deviation callout
 * correctly identifies the more-deviating of two attack types, and the
 * ranking-delta callout correctly identifies a country with zero prior-year
 * presence that appears once new data exists.
 *
 * <p>Uses attack types ("Hijacking"/"Facility/Infrastructure Attack") and a
 * country ("Kazakhstan") no other test touches, plus a distinctive future
 * year range (2093/2094, following {@code TrendServiceTest}'s pattern) so
 * this test's global "latest year" reads (INSIGHT queries the materialized
 * views, which reflect the whole shared test database) aren't disturbed by
 * other test classes' data.
 *
 * <p>Materialized views don't auto-update on {@code INSERT} - a plain
 * (non-{@code CONCURRENTLY}) {@code REFRESH} after seeding is required
 * before INSIGHT's queries can see the new rows.
 */
@SpringBootTest
class InsightServiceTest extends PostgresIntegrationTestBase {

    @Autowired
    private InsightService insightService;
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
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DatasetVersion datasetVersion;

    @BeforeEach
    void setUp() {
        datasetVersion = FactIncidentTestFixtures.createDatasetVersion(importBatchRepository, datasetVersionRepository);
    }

    @Test
    void generatesBothInsightTypesFromRealData() {
        var country = countryRepository.findByName("United States").orElseThrow();
        var kazakhstan = countryRepository.findByName("Kazakhstan").orElseThrow();
        var canada = countryRepository.findByName("Canada").orElseThrow();
        DimAttackType deviatingType = attackTypeRepository.findByName("Hostage Taking (Barricade Incident)").orElseThrow();
        DimAttackType stableType = attackTypeRepository.findByName("Facility/Infrastructure Attack").orElseThrow();
        DimAttackType rankingScenarioType = attackTypeRepository.findByName("Assassination").orElseThrow();
        var weaponType = weaponTypeRepository.findByName("Melee").orElseThrow();
        var targetType = targetTypeRepository.findByName("Transportation").orElseThrow();
        var org = organizationRepository.findByName("Basque Fatherland and Freedom (ETA)").orElseThrow();

        // Growth-rate deviation: "Hijacking" jumps 1 -> 5 (400%), "Facility/
        // Infrastructure Attack" stays flat at 1 -> 1 (0%) - Hijacking must win.
        saveIncidents(country, deviatingType, weaponType, targetType, org, 2093, 1);
        saveIncidents(country, deviatingType, weaponType, targetType, org, 2094, 5);
        saveIncidents(country, stableType, weaponType, targetType, org, 2093, 1);
        saveIncidents(country, stableType, weaponType, targetType, org, 2094, 1);

        // Ranking delta: United States and Canada present in both years;
        // Kazakhstan has zero presence in 2093 and appears only in 2094. Uses
        // its own attack type (not stableType) so it can't skew the
        // growth-rate control group above.
        saveIncidents(country, rankingScenarioType, weaponType, targetType, org, 2093, 3);
        saveIncidents(country, rankingScenarioType, weaponType, targetType, org, 2094, 3);
        saveIncidents(canada, rankingScenarioType, weaponType, targetType, org, 2093, 2);
        saveIncidents(canada, rankingScenarioType, weaponType, targetType, org, 2094, 2);
        saveIncidents(kazakhstan, rankingScenarioType, weaponType, targetType, org, 2094, 10);

        jdbcTemplate.execute("REFRESH MATERIALIZED VIEW mv_attacktype_year_agg");
        jdbcTemplate.execute("REFRESH MATERIALIZED VIEW mv_country_year_agg");

        // Scoped to exactly this test's own 2093-2094 window - INSIGHT only respects the
        // date-range half of the canonical filter (see InsightService's Javadoc), and both the
        // growth-rate-deviation and ranking-delta callouts key off an unfiltered MAX(event_year)
        // read across the whole shared, never-rolled-back test database otherwise. That
        // "latest year" assumption held only so long as no other test class ever inserted a
        // later year than 2094 - which stopped being true once a Phase 8 workspace integration
        // test (importing real rows at a later synthetic year) ran before this one in the same
        // suite. An unfiltered call here is fundamentally order-dependent on the whole suite,
        // not just this test's own fixture.
        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setStartDate(java.time.LocalDate.of(2093, 1, 1));
        filter.setEndDate(java.time.LocalDate.of(2094, 12, 31));
        InsightsResponse insights = insightService.generateExecutiveInsights(filter);

        assertThat(insights.items()).hasSizeGreaterThanOrEqualTo(2);

        boolean foundDeviationInsight = insights.items().stream()
                .anyMatch(item -> item.text().contains("Hostage Taking") && item.meta().contains("SAV-008"));
        assertThat(foundDeviationInsight)
                .as("growth-rate deviation insight should name the deviating attack type (Hostage Taking), not the stable one")
                .isTrue();

        boolean foundRankingInsight = insights.items().stream()
                .anyMatch(item -> item.text().contains("Kazakhstan") && item.meta().contains("CAC-AN-002"));
        assertThat(foundRankingInsight)
                .as("ranking delta insight should name the new entrant (Kazakhstan)")
                .isTrue();
    }

    private void saveIncidents(DimCountry country, DimAttackType attackType,
            com.gtd.platform.shared.db.DimWeaponType weaponType, com.gtd.platform.shared.db.DimTargetType targetType,
            com.gtd.platform.shared.db.DimOrganization organization, int year, int count) {
        for (int i = 0; i < count; i++) {
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
            incident.setCreatedAt(Instant.now());
            factIncidentRepository.save(incident);
        }
    }
}

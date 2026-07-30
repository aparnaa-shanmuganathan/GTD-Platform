package com.gtd.platform.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gtd.platform.shared.db.ImportBatch.ImportMode;
import com.gtd.platform.shared.db.ImportBatch.ImportStatus;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the Phase 1 schema (CLAUDE.md section 7) end to end against a
 * real PostgreSQL instance: all nine V1-V9 migrations apply cleanly, the
 * seeded reference dimension data is present, the "exactly one active
 * dataset_version" partial unique index actually rejects a second active
 * row, and all six mv_*_year_agg materialized views can be refreshed
 * CONCURRENTLY without error against real fact_incident data.
 */
@SpringBootTest
class SchemaIntegrationTest extends PostgresIntegrationTestBase {

    private static final List<String> MATERIALIZED_VIEWS = List.of(
            "mv_country_year_agg",
            "mv_region_year_agg",
            "mv_organization_year_agg",
            "mv_attacktype_year_agg",
            "mv_weapontype_year_agg",
            "mv_targettype_year_agg"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DimRegionRepository dimRegionRepository;

    @Autowired
    private DimCountryRepository dimCountryRepository;

    @Autowired
    private DimOrganizationRepository dimOrganizationRepository;

    @Autowired
    private DimAttackTypeRepository dimAttackTypeRepository;

    @Autowired
    private DimWeaponTypeRepository dimWeaponTypeRepository;

    @Autowired
    private DimTargetTypeRepository dimTargetTypeRepository;

    @Autowired
    private ImportBatchRepository importBatchRepository;

    @Autowired
    private DatasetVersionRepository datasetVersionRepository;

    @Autowired
    private FactIncidentRepository factIncidentRepository;

    @Autowired
    private MaterializedViewStatusRepository materializedViewStatusRepository;

    @Test
    void migrationsRunCleanlyAndSeedDataIsPresent() {
        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThat(appliedMigrations).isEqualTo(10); // V1-V9 (Phase 1) + V10 (Phase 3: import_rejected_row)

        // 12 canonical GTD regions + Unknown
        assertThat(dimRegionRepository.count()).isEqualTo(13);
        assertThat(dimRegionRepository.findByUnknownTrue()).isPresent();

        // Representative handful of countries spanning every region + Unknown
        assertThat(dimCountryRepository.count()).isEqualTo(26);
        assertThat(dimCountryRepository.findByUnknownTrue()).isPresent();

        // Full canonical GTD attack type domain (9) including Unknown
        assertThat(dimAttackTypeRepository.count()).isEqualTo(9);
        assertThat(dimAttackTypeRepository.findByUnknownTrue()).isPresent();

        // Full canonical GTD weapon type domain (13) including Unknown
        assertThat(dimWeaponTypeRepository.count()).isEqualTo(13);
        assertThat(dimWeaponTypeRepository.findByUnknownTrue()).isPresent();

        // Full canonical GTD target type domain (22) including Unknown
        assertThat(dimTargetTypeRepository.count()).isEqualTo(22);
        assertThat(dimTargetTypeRepository.findByUnknownTrue()).isPresent();

        // Representative handful of organizations + Unknown
        assertThat(dimOrganizationRepository.count()).isEqualTo(9);
        assertThat(dimOrganizationRepository.findByUnknownTrue()).isPresent();

        assertThat(materializedViewStatusRepository.count()).isEqualTo(6);
        assertThat(materializedViewStatusRepository.findAllById(MATERIALIZED_VIEWS)).hasSize(6);
    }

    @Test
    void exactlyOneActiveDatasetVersionConstraintRejectsSecondActiveRow() {
        // Other test classes sharing this same database (no rollback between them - see
        // PostgresIntegrationTestBase) may have already activated their own dataset_version
        // and left it active (a real import-confirm flow deactivates any prior one before
        // activating a new one, but never leaves zero active versions). Clear that first so
        // this test's own "first" insert below is guaranteed to be the only active row,
        // not itself an unexpected constraint violation.
        jdbcTemplate.update("UPDATE dataset_version SET is_active = false WHERE is_active = true");

        ImportBatch batch = persistImportBatch();

        DatasetVersion first = new DatasetVersion();
        first.setVersionLabel("v-active-test-1");
        first.setImportBatch(batch);
        first.setActive(true);
        first.setCreatedAt(Instant.now());
        datasetVersionRepository.saveAndFlush(first);

        DatasetVersion second = new DatasetVersion();
        second.setVersionLabel("v-active-test-2");
        second.setImportBatch(batch);
        second.setActive(true);
        second.setCreatedAt(Instant.now());

        assertThatThrownBy(() -> datasetVersionRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void materializedViewsRefreshConcurrentlyWithoutErrorAgainstSeedData() {
        DimCountry unitedStates = dimCountryRepository.findByName("United States").orElseThrow();
        DimAttackType bombing = dimAttackTypeRepository.findByName("Bombing/Explosion").orElseThrow();
        DimWeaponType explosives = dimWeaponTypeRepository.findByName("Explosives").orElseThrow();
        DimTargetType business = dimTargetTypeRepository.findByName("Business").orElseThrow();
        DimOrganization unknownOrg = dimOrganizationRepository.findByUnknownTrue().orElseThrow();

        ImportBatch batch = persistImportBatch();

        DatasetVersion datasetVersion = new DatasetVersion();
        datasetVersion.setVersionLabel("v-mv-refresh-test");
        datasetVersion.setImportBatch(batch);
        datasetVersion.setActive(false);
        datasetVersion.setCreatedAt(Instant.now());
        datasetVersionRepository.saveAndFlush(datasetVersion);

        FactIncident incident = new FactIncident();
        incident.setEventId("202401010001");
        incident.setDatasetVersion(datasetVersion);
        incident.setCountry(unitedStates);
        incident.setRegion(unitedStates.getRegion());
        incident.setAttackType(bombing);
        incident.setWeaponType(explosives);
        incident.setTargetType(business);
        incident.setOrganization(unknownOrg);
        incident.setEventYear(2024);
        incident.setNkill(3);
        incident.setNwound(5);
        incident.setSuccess(true);
        incident.setCreatedAt(Instant.now());
        factIncidentRepository.saveAndFlush(incident);

        for (String view : MATERIALIZED_VIEWS) {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view);
        }

        Map<String, Object> countryYearRow = jdbcTemplate.queryForMap(
                "SELECT * FROM mv_country_year_agg WHERE country_id = ? AND event_year = ?",
                unitedStates.getId(), 2024);
        assertThat(countryYearRow.get("incident_count")).isEqualTo(1L);
        assertThat(countryYearRow.get("fatality_count")).isEqualTo(3L);
        assertThat(countryYearRow.get("injury_count")).isEqualTo(5L);
        assertThat(countryYearRow.get("success_count")).isEqualTo(1L);
    }

    private ImportBatch persistImportBatch() {
        ImportBatch batch = new ImportBatch();
        batch.setFileName("schema-integration-test.csv");
        batch.setFileSizeBytes(1024L);
        batch.setImportMode(ImportMode.APPEND);
        batch.setStatus(ImportStatus.COMPLETED);
        batch.setCreatedAt(Instant.now());
        return importBatchRepository.saveAndFlush(batch);
    }
}

package com.gtd.platform.shared.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.ImportBatchRepository;
import com.gtd.platform.testsupport.FactIncidentTestFixtures;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

/**
 * Verifies {@code generated_report}'s V8-migration triggers actually reject
 * both UPDATE and DELETE against a real PostgreSQL instance - not just that
 * the application-level convention is "don't do that" (CLAUDE.md section 12,
 * Phase 1 decisions: immutability is DB-trigger-enforced specifically so it
 * can't be silently bypassed by a future repository method, a raw SQL
 * session, or an ORM bug). Asserts on {@link DataAccessException} (Spring's
 * common root for translated database errors) rather than the more specific
 * {@code DataIntegrityViolationException} {@code SchemaIntegrationTest}'s
 * unique-constraint case uses: a bare {@code RAISE EXCEPTION} in a trigger
 * (this one, {@code fn_reject_table_mutation()}, has no explicit SQLSTATE)
 * translates to {@code JpaSystemException}, not
 * {@code DataIntegrityViolationException}, which only specific SQLSTATEs
 * (unique/FK/check/not-null violations) map to.
 */
@SpringBootTest
class GeneratedReportImmutabilityTest extends PostgresIntegrationTestBase {

    @Autowired
    private GeneratedReportRepository generatedReportRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private DatasetVersionRepository datasetVersionRepository;

    @Test
    void updatingAGeneratedReportIsRejectedByTheDatabase() {
        GeneratedReport saved = saveReport();

        saved.setReportName("A new name after the fact");

        assertThatThrownBy(() -> generatedReportRepository.saveAndFlush(saved))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void deletingAGeneratedReportIsRejectedByTheDatabase() {
        GeneratedReport saved = saveReport();

        assertThatThrownBy(() -> {
            generatedReportRepository.delete(saved);
            generatedReportRepository.flush();
        }).isInstanceOf(DataAccessException.class);
    }

    private GeneratedReport saveReport() {
        DatasetVersion datasetVersion = FactIncidentTestFixtures.createDatasetVersion(importBatchRepository, datasetVersionRepository);

        GeneratedReport report = new GeneratedReport();
        report.setReportName("Immutability test report");
        report.setFormat(GeneratedReport.Format.PDF);
        report.setStatus(GeneratedReport.Status.GENERATED);
        report.setDatasetVersion(datasetVersion);
        report.setFilterSnapshot("{}");
        report.setGeneratedAt(Instant.now());

        GeneratedReport saved = generatedReportRepository.saveAndFlush(report);
        assertThat(saved.getId()).isNotNull();
        return saved;
    }
}

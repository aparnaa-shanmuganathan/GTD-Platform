package com.gtd.platform.testsupport;

import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.ImportBatch;
import com.gtd.platform.shared.db.ImportBatchRepository;
import java.time.Instant;

/** Shared seed-data scaffolding for tests that need real {@code fact_incident} rows against known values. */
public final class FactIncidentTestFixtures {

    private FactIncidentTestFixtures() {
    }

    public static DatasetVersion createDatasetVersion(ImportBatchRepository importBatchRepository,
            DatasetVersionRepository datasetVersionRepository) {
        ImportBatch batch = new ImportBatch();
        batch.setFileName("test-fixture.csv");
        batch.setFileSizeBytes(1024L);
        batch.setImportMode(ImportBatch.ImportMode.APPEND);
        batch.setStatus(ImportBatch.ImportStatus.COMPLETED);
        batch.setCreatedAt(Instant.now());
        ImportBatch savedBatch = importBatchRepository.save(batch);

        DatasetVersion version = new DatasetVersion();
        version.setVersionLabel("test-fixture-version-" + System.nanoTime());
        version.setImportBatch(savedBatch);
        version.setActive(false);
        version.setCreatedAt(Instant.now());
        return datasetVersionRepository.save(version);
    }
}

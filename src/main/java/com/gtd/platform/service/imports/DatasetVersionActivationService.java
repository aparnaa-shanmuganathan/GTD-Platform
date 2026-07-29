package com.gtd.platform.service.imports;

import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.FactIncidentRepository;
import com.gtd.platform.shared.db.ImportBatch;
import com.gtd.platform.shared.db.ImportBatch.ImportStatus;
import com.gtd.platform.shared.db.ImportBatchRepository;
import com.gtd.platform.shared.exception.ApiException;
import com.gtd.platform.shared.exception.ErrorCode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "activate this dataset_version" confirmation step (CLAUDE.md section
 * 8, AWS-IMPORT-001): deactivates whichever {@code dataset_version} is
 * currently active, activates the one this import batch produced, and marks
 * the batch {@code COMPLETED} - all one atomic transaction, respecting the
 * partial unique index enforcing exactly one active row (V3 migration).
 *
 * <p>Kept as its own bean (not a method on {@link ImportConfirmationService})
 * specifically so its {@code @Transactional} boundary is real: calling it
 * from another method on the <em>same</em> object would bypass Spring's
 * proxy-based transaction interception (self-invocation), silently running
 * without a transaction.
 */
@Service
public class DatasetVersionActivationService {

    private final ImportBatchRepository importBatchRepository;
    private final DatasetVersionRepository datasetVersionRepository;
    private final FactIncidentRepository factIncidentRepository;

    public DatasetVersionActivationService(ImportBatchRepository importBatchRepository,
            DatasetVersionRepository datasetVersionRepository, FactIncidentRepository factIncidentRepository) {
        this.importBatchRepository = importBatchRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.factIncidentRepository = factIncidentRepository;
    }

    @Transactional
    public DatasetVersion activate(long importBatchId) {
        ImportBatch batch = importBatchRepository.findById(importBatchId)
                .orElseThrow(() -> new ApiException(ErrorCode.ERR_IMPORT_004));

        if (batch.getStatus() != ImportStatus.AWAITING_CONFIRMATION) {
            throw new ApiException(ErrorCode.ERR_IMPORT_005,
                    "Import batch " + importBatchId + " is not awaiting confirmation (status: " + batch.getStatus() + ")");
        }

        DatasetVersion newVersion = datasetVersionRepository.findByImportBatch_Id(importBatchId)
                .orElseThrow(() -> new IllegalStateException(
                        "Import batch " + importBatchId + " has no dataset_version - normalization must not have run"));

        // saveAndFlush (not save) for the deactivation: the partial unique
        // index enforcing exactly one active row is checked per-statement,
        // not deferred to commit, so this UPDATE must physically execute
        // before the activation UPDATE below is issued - otherwise Hibernate
        // is free to reorder or batch both into the same flush and could
        // send them in either order.
        datasetVersionRepository.findByActiveTrue().ifPresent(currentlyActive -> {
            currentlyActive.setActive(false);
            datasetVersionRepository.saveAndFlush(currentlyActive);
        });

        newVersion.setActive(true);
        newVersion.setActivatedAt(Instant.now());
        newVersion.setRowCount((int) factIncidentRepository.count());
        datasetVersionRepository.save(newVersion);

        batch.setStatus(ImportStatus.COMPLETED);
        batch.setCompletedAt(Instant.now());
        importBatchRepository.save(batch);

        return newVersion;
    }
}

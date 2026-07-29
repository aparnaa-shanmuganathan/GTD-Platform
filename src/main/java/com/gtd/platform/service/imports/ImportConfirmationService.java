package com.gtd.platform.service.imports;

import com.gtd.platform.shared.db.DatasetVersion;
import org.springframework.stereotype.Service;

/**
 * The one entry point for AWS-IMPORT-001's "final confirmation step" - the
 * only part of the IMPORT pipeline that requires an active session
 * ({@link ImportController} sits behind normal authentication like every
 * other endpoint, so this is enforced simply by being a controller method,
 * not by anything special in this class).
 *
 * <p>Composes two independently-transactional steps in sequence, on purpose:
 * {@link DatasetVersionActivationService#activate} (one atomic DB
 * transaction) must fully commit before {@link MaterializedViewRefreshService#refreshAll}
 * runs, since {@code REFRESH MATERIALIZED VIEW CONCURRENTLY} is rejected by
 * PostgreSQL inside a transaction block and must see the newly-activated
 * data.
 */
@Service
public class ImportConfirmationService {

    private final DatasetVersionActivationService datasetVersionActivationService;
    private final MaterializedViewRefreshService materializedViewRefreshService;

    public ImportConfirmationService(DatasetVersionActivationService datasetVersionActivationService,
            MaterializedViewRefreshService materializedViewRefreshService) {
        this.datasetVersionActivationService = datasetVersionActivationService;
        this.materializedViewRefreshService = materializedViewRefreshService;
    }

    public DatasetVersion confirm(long importBatchId) {
        DatasetVersion activated = datasetVersionActivationService.activate(importBatchId);
        materializedViewRefreshService.refreshAll();
        return activated;
    }
}

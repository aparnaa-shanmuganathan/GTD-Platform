package com.gtd.platform.service.imports;

import com.gtd.platform.shared.db.ImportBatch;
import com.gtd.platform.shared.db.ImportBatch.ImportMode;
import com.gtd.platform.shared.db.ImportBatch.ImportStatus;
import com.gtd.platform.shared.db.ImportBatchRepository;
import com.gtd.platform.shared.exception.ApiException;
import com.gtd.platform.shared.exception.ErrorCode;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * The synchronous half of the IMPORT pipeline (CLAUDE.md section 8,
 * AWS-IMPORT-001): acquires the advisory lock and creates the
 * {@code import_batch} row before returning, so the caller gets an
 * immediate accept/reject decision and a batch id to poll - then hands off
 * to {@link ImportJobRunner} (a separate bean; see its Javadoc for why) for
 * the actual background work.
 */
@Service
public class ImportOrchestrationService {

    private final ImportAdvisoryLockService lockService;
    private final ImportBatchRepository importBatchRepository;
    private final ImportJobRunner importJobRunner;

    public ImportOrchestrationService(ImportAdvisoryLockService lockService, ImportBatchRepository importBatchRepository,
            ImportJobRunner importJobRunner) {
        this.lockService = lockService;
        this.importBatchRepository = importBatchRepository;
        this.importJobRunner = importJobRunner;
    }

    public ImportBatch initiateImport(String fileName, long fileSizeBytes, ImportMode mode,
            Long initiatedByAdminUserId, Path stagedCsvPath) {
        ImportAdvisoryLockService.Handle lockHandle;
        try {
            lockHandle = lockService.tryAcquire().orElseThrow(() -> new ApiException(ErrorCode.ERR_IMPORT_003));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check the import advisory lock", e);
        }

        ImportBatch batch = new ImportBatch();
        batch.setFileName(fileName);
        batch.setFileSizeBytes(fileSizeBytes);
        batch.setImportMode(mode);
        batch.setStatus(ImportStatus.PENDING);
        batch.setInitiatedByAdminUserId(initiatedByAdminUserId);
        batch.setStartedAt(Instant.now());
        batch.setCreatedAt(Instant.now());
        batch = importBatchRepository.save(batch);

        importJobRunner.runImportAsync(batch.getId(), stagedCsvPath, mode, lockHandle);
        return batch;
    }
}

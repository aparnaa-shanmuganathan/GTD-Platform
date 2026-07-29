package com.gtd.platform.service.imports;

import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.ImportBatch;
import com.gtd.platform.shared.db.ImportBatch.ImportMode;
import com.gtd.platform.shared.db.ImportBatch.ImportStatus;
import com.gtd.platform.shared.db.ImportBatchRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * The actual staging -&gt; normalization background job (CLAUDE.md section 8,
 * AWS-IMPORT-001) - split out from {@link ImportOrchestrationService} as its
 * own bean specifically so {@link #runImportAsync} is a genuine
 * cross-proxy call: {@code @Async} only takes effect through Spring's AOP
 * proxy, and a same-object (self-invocation) call would silently run this
 * synchronously on the caller's thread instead, defeating the entire point.
 */
@Service
public class ImportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportJobRunner.class);
    private static final int NORMALIZE_BATCH_SIZE = 2000;

    private final ImportBatchRepository importBatchRepository;
    private final DatasetVersionRepository datasetVersionRepository;
    private final CsvStagingLoader csvStagingLoader;
    private final StagingRowReader stagingRowReader;
    private final RowNormalizer rowNormalizer;
    private final DimensionResolver dimensionResolver;
    private final FactIncidentBulkWriter factIncidentBulkWriter;
    private final RejectedRowRepository rejectedRowRepository;

    public ImportJobRunner(ImportBatchRepository importBatchRepository, DatasetVersionRepository datasetVersionRepository,
            CsvStagingLoader csvStagingLoader, StagingRowReader stagingRowReader, RowNormalizer rowNormalizer,
            DimensionResolver dimensionResolver, FactIncidentBulkWriter factIncidentBulkWriter,
            RejectedRowRepository rejectedRowRepository) {
        this.importBatchRepository = importBatchRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.csvStagingLoader = csvStagingLoader;
        this.stagingRowReader = stagingRowReader;
        this.rowNormalizer = rowNormalizer;
        this.dimensionResolver = dimensionResolver;
        this.factIncidentBulkWriter = factIncidentBulkWriter;
        this.rejectedRowRepository = rejectedRowRepository;
    }

    @Async(ImportAsyncConfig.IMPORT_EXECUTOR_BEAN)
    public void runImportAsync(long importBatchId, Path csvPath, ImportMode mode, ImportAdvisoryLockService.Handle lockHandle) {
        try {
            doRunImport(importBatchId, csvPath, mode);
        } catch (Exception e) {
            log.error("Import batch {} failed", importBatchId, e);
            markFailed(importBatchId, e);
        } finally {
            lockHandle.close();
            deleteQuietly(csvPath);
        }
    }

    private void doRunImport(long importBatchId, Path csvPath, ImportMode mode) throws IOException, SQLException {
        updateStatus(importBatchId, ImportStatus.STAGING);
        CsvStagingLoader.StagingResult stagingResult = csvStagingLoader.stage(
                csvPath, importBatchId, staged -> updateRowsStaged(importBatchId, staged));
        finalizeStagingCounts(importBatchId, stagingResult.totalRowsStaged());

        DatasetVersion datasetVersion = createDatasetVersion(importBatchId);

        updateStatus(importBatchId, ImportStatus.NORMALIZING);
        dimensionResolver.initializeCaches();

        List<FactIncidentRecord> pendingWrites = new ArrayList<>(NORMALIZE_BATCH_SIZE);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        stagingRowReader.streamRows(importBatchId, row -> {
            NormalizationResult result = rowNormalizer.normalize(row, datasetVersion.getId());
            switch (result) {
                case NormalizationResult.Accepted accepted -> pendingWrites.add(accepted.factIncident());
                case NormalizationResult.Rejected rejected -> {
                    recordRejection(importBatchId, row.id(), row.eventId(), rejected.reason(), rejected.detail());
                    rejectedCount.incrementAndGet();
                }
            }

            if (pendingWrites.size() >= NORMALIZE_BATCH_SIZE) {
                flushWrites(importBatchId, pendingWrites, mode, rejectedCount);
                updateRowsRejected(importBatchId, rejectedCount.get());
            }
        });

        if (!pendingWrites.isEmpty()) {
            flushWrites(importBatchId, pendingWrites, mode, rejectedCount);
        }
        updateRowsRejected(importBatchId, rejectedCount.get());

        updateStatus(importBatchId, ImportStatus.AWAITING_CONFIRMATION);
    }

    private void flushWrites(long importBatchId, List<FactIncidentRecord> batch, ImportMode mode, AtomicInteger rejectedCount) {
        List<FactIncidentRecord> skipped = factIncidentBulkWriter.writeBatch(batch, mode);
        for (FactIncidentRecord record : skipped) {
            recordRejection(importBatchId, record.stagingIncidentId(), record.eventId(),
                    RejectionReason.DUPLICATE_EVENTID_APPEND_SKIPPED,
                    "eventid already exists in fact_incident (APPEND mode leaves existing rows untouched)");
            rejectedCount.incrementAndGet();
        }
        batch.clear();
    }

    private DatasetVersion createDatasetVersion(long importBatchId) {
        ImportBatch batch = importBatchRepository.findById(importBatchId).orElseThrow();
        DatasetVersion version = new DatasetVersion();
        version.setVersionLabel("import-" + importBatchId + "-" + Instant.now());
        version.setImportBatch(batch);
        version.setActive(false);
        version.setCreatedAt(Instant.now());
        return datasetVersionRepository.save(version);
    }

    private void recordRejection(long importBatchId, long stagingIncidentId, String eventId,
            RejectionReason reason, String detail) {
        RejectedRow rejectedRow = new RejectedRow();
        rejectedRow.setImportBatchId(importBatchId);
        rejectedRow.setStagingIncidentId(stagingIncidentId);
        rejectedRow.setEventId(eventId);
        rejectedRow.setRejectionReason(reason.name());
        rejectedRow.setRejectionDetail(detail);
        rejectedRow.setRejectedAt(Instant.now());
        rejectedRowRepository.save(rejectedRow);
    }

    // No @Transactional here: these are called from within this same class
    // (doRunImport/runImportAsync), so a class-level annotation would be a
    // no-op proxy self-invocation, not real atomicity - misleading to write.
    // Each is a single find-then-save against one row with no other writer
    // (the advisory lock guarantees only this job touches import_batch
    // during a run), and findById/save are themselves already transactional
    // via SimpleJpaRepository, so two independent per-call transactions here
    // is correct as-is, not a gap.

    void updateStatus(long importBatchId, ImportStatus status) {
        ImportBatch batch = importBatchRepository.findById(importBatchId).orElseThrow();
        batch.setStatus(status);
        importBatchRepository.save(batch);
    }

    void updateRowsStaged(long importBatchId, int rowsStaged) {
        ImportBatch batch = importBatchRepository.findById(importBatchId).orElseThrow();
        batch.setRowsStaged(rowsStaged);
        importBatchRepository.save(batch);
    }

    void finalizeStagingCounts(long importBatchId, int totalRowsStaged) {
        ImportBatch batch = importBatchRepository.findById(importBatchId).orElseThrow();
        batch.setRowsStaged(totalRowsStaged);
        batch.setTotalRows(totalRowsStaged);
        importBatchRepository.save(batch);
    }

    void updateRowsRejected(long importBatchId, int rowsRejected) {
        ImportBatch batch = importBatchRepository.findById(importBatchId).orElseThrow();
        batch.setRowsRejected(rowsRejected);
        importBatchRepository.save(batch);
    }

    void markFailed(long importBatchId, Exception cause) {
        ImportBatch batch = importBatchRepository.findById(importBatchId).orElseThrow();
        batch.setStatus(ImportStatus.FAILED);
        batch.setErrorMessage(String.valueOf(cause.getMessage()));
        batch.setCompletedAt(Instant.now());
        importBatchRepository.save(batch);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete staged import file {}", path, e);
        }
    }
}

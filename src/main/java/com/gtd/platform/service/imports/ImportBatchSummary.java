package com.gtd.platform.service.imports;

import com.gtd.platform.shared.db.ImportBatch;
import java.time.Instant;

/**
 * A read projection of {@link ImportBatch}, reused by both the Administration Workspace's
 * import history panel and the Data Quality Center's batch list (Phase 10) - lives in {@code
 * service.imports} (a cross-cutting service, not either workspace) so neither workspace package
 * depends on the other, the same reasoning {@code service.report.ReportSummary}/{@code
 * service.compare.CountryDashboardEntry} already established for shared read-projection shapes.
 */
public record ImportBatchSummary(
        Long id,
        String fileName,
        String importMode,
        String status,
        Integer totalRows,
        int rowsStaged,
        int rowsRejected,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt) {

    public static ImportBatchSummary from(ImportBatch batch) {
        return new ImportBatchSummary(batch.getId(), batch.getFileName(), batch.getImportMode().name(),
                batch.getStatus().name(), batch.getTotalRows(), batch.getRowsStaged(), batch.getRowsRejected(),
                batch.getStartedAt(), batch.getCompletedAt(), batch.getCreatedAt());
    }
}

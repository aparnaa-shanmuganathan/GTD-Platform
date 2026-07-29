package com.gtd.platform.service.imports;

import java.time.Instant;

/** The progress/status polling response - the current phase plus the row counters {@code import_batch} tracks. */
public record ImportStatusResponse(
        Long importBatchId,
        String status,
        String importMode,
        Integer totalRows,
        Integer rowsStaged,
        Integer rowsRejected,
        Instant startedAt,
        Instant completedAt,
        String errorMessage
) {
}

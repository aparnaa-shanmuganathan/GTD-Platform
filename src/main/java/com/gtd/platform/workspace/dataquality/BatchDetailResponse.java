package com.gtd.platform.workspace.dataquality;

import com.gtd.platform.service.imports.ImportBatchSummary;
import java.util.List;

/** {@code POST /api/dataquality/batch-detail}'s response - a batch's rejection breakdown plus a bounded raw-row list. */
public record BatchDetailResponse(
        ImportBatchSummary batch,
        List<RejectionBreakdownEntry> breakdown,
        List<RejectedRowDetail> rejectedRows,
        long totalRejectedRows) {
}

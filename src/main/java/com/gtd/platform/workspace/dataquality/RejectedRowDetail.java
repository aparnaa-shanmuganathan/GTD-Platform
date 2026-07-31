package com.gtd.platform.workspace.dataquality;

import com.gtd.platform.service.imports.RejectedRow;
import java.time.Instant;

/** A single rejected-row detail row (Phase 10) - a read projection of {@link RejectedRow}. */
public record RejectedRowDetail(Long id, String eventId, String rejectionReason, String rejectionDetail, Instant rejectedAt) {

    public static RejectedRowDetail from(RejectedRow row) {
        return new RejectedRowDetail(row.getId(), row.getEventId(), row.getRejectionReason(),
                row.getRejectionDetail(), row.getRejectedAt());
    }
}

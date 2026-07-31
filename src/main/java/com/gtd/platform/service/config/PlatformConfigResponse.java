package com.gtd.platform.service.config;

import com.gtd.platform.shared.db.ImportBatch.ImportMode;
import java.time.Instant;

/** {@code POST /api/config}'s response shape - the current, real settings, never a placeholder. */
public record PlatformConfigResponse(
        int sessionTimeoutMinutes,
        ImportMode defaultImportMode,
        boolean materializedViewAutoRefresh,
        boolean notifyOnImportCompletion,
        Instant updatedAt) {
}

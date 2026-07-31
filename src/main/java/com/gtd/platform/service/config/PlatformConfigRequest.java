package com.gtd.platform.service.config;

import com.gtd.platform.shared.db.ImportBatch.ImportMode;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/config/save}'s request body. {@code sessionTimeoutMinutes}'s
 * 5-1440 range and {@code defaultImportMode}'s enum membership are both
 * re-validated in {@link PlatformConfigService#save} (not just relied on as
 * a DB CHECK constraint) so a bad value fails with {@code ERR-CONFIG-001}
 * and a clear message rather than a raw constraint-violation SQL error.
 */
public record PlatformConfigRequest(
        @NotNull Integer sessionTimeoutMinutes,
        @NotNull ImportMode defaultImportMode,
        @NotNull Boolean materializedViewAutoRefresh,
        @NotNull Boolean notifyOnImportCompletion) {
}

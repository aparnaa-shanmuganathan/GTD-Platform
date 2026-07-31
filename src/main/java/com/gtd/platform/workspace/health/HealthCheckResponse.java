package com.gtd.platform.workspace.health;

import com.gtd.platform.workspace.health.OfflineAssetScanner.Finding;
import java.time.Instant;
import java.util.List;

/** {@code POST /api/health/check}'s response - every field is a real, freshly-computed check, never a hardcoded status. */
public record HealthCheckResponse(
        DbConnectionStatus database,
        ActiveDatasetVersionStatus activeDatasetVersion,
        List<MaterializedViewFreshness> materializedViews,
        OfflineVerification offlineVerification,
        Instant checkedAt) {

    public record DbConnectionStatus(boolean connected, long responseTimeMs) {
    }

    public record ActiveDatasetVersionStatus(Long id, String versionLabel, Instant activatedAt, Integer rowCount) {
    }

    public record MaterializedViewFreshness(
            String viewName, Instant lastRefreshedAt, String lastRefreshStatus, Long lastRefreshDurationMs,
            Long rowCount, boolean healthy) {
    }

    public record OfflineVerification(boolean passed, int resourcesScanned, List<Finding> findings) {
    }
}

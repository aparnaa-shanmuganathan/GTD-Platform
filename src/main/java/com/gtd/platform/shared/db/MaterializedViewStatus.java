package com.gtd.platform.shared.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps to {@code materialized_view_status} (CLAUDE.md section 7). Rows are
 * seeded by V5 for the six {@code mv_*_year_agg} views; Phase 3's IMPORT
 * completion step is responsible for updating a row after each
 * {@code REFRESH MATERIALIZED VIEW CONCURRENTLY}.
 */
@Entity
@Table(name = "materialized_view_status")
public class MaterializedViewStatus {

    @Id
    @Column(name = "view_name", length = 100)
    private String viewName;

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    @Column(name = "last_refresh_duration_ms")
    private Long lastRefreshDurationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_refresh_status", nullable = false, length = 20)
    private RefreshStatus lastRefreshStatus = RefreshStatus.NEVER_REFRESHED;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getViewName() {
        return viewName;
    }

    public void setViewName(String viewName) {
        this.viewName = viewName;
    }

    public Instant getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public void setLastRefreshedAt(Instant lastRefreshedAt) {
        this.lastRefreshedAt = lastRefreshedAt;
    }

    public Long getLastRefreshDurationMs() {
        return lastRefreshDurationMs;
    }

    public void setLastRefreshDurationMs(Long lastRefreshDurationMs) {
        this.lastRefreshDurationMs = lastRefreshDurationMs;
    }

    public RefreshStatus getLastRefreshStatus() {
        return lastRefreshStatus;
    }

    public void setLastRefreshStatus(RefreshStatus lastRefreshStatus) {
        this.lastRefreshStatus = lastRefreshStatus;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public void setRowCount(Long rowCount) {
        this.rowCount = rowCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public enum RefreshStatus {
        NEVER_REFRESHED,
        SUCCESS,
        FAILED
    }
}

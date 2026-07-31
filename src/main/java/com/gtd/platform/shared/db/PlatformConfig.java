package com.gtd.platform.shared.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps to {@code platform_config} (CLAUDE.md section 12, "Phase 10
 * decisions"): a single fixed row (id = 1, enforced by a DB CHECK
 * constraint) holding the CONFIG cross-cutting service's settings. Lives in
 * {@code shared.db} for the same reason {@link ImportBatch}/{@link
 * DatasetVersion} do - {@code service.config} depends on it, not the
 * reverse.
 */
@Entity
@Table(name = "platform_config")
public class PlatformConfig {

    @Id
    private Long id = 1L;

    @Column(name = "session_timeout_minutes", nullable = false)
    private int sessionTimeoutMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_import_mode", nullable = false, length = 20)
    private ImportBatch.ImportMode defaultImportMode;

    @Column(name = "materialized_view_auto_refresh", nullable = false)
    private boolean materializedViewAutoRefresh;

    @Column(name = "notify_on_import_completion", nullable = false)
    private boolean notifyOnImportCompletion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_admin_user_id")
    private Long updatedByAdminUserId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public ImportBatch.ImportMode getDefaultImportMode() {
        return defaultImportMode;
    }

    public void setDefaultImportMode(ImportBatch.ImportMode defaultImportMode) {
        this.defaultImportMode = defaultImportMode;
    }

    public boolean isMaterializedViewAutoRefresh() {
        return materializedViewAutoRefresh;
    }

    public void setMaterializedViewAutoRefresh(boolean materializedViewAutoRefresh) {
        this.materializedViewAutoRefresh = materializedViewAutoRefresh;
    }

    public boolean isNotifyOnImportCompletion() {
        return notifyOnImportCompletion;
    }

    public void setNotifyOnImportCompletion(boolean notifyOnImportCompletion) {
        this.notifyOnImportCompletion = notifyOnImportCompletion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getUpdatedByAdminUserId() {
        return updatedByAdminUserId;
    }

    public void setUpdatedByAdminUserId(Long updatedByAdminUserId) {
        this.updatedByAdminUserId = updatedByAdminUserId;
    }
}

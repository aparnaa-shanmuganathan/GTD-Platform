package com.gtd.platform.shared.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps to {@code import_batch} (CLAUDE.md section 7). Lives in {@code shared.db}
 * rather than {@code service.imports}: {@link DatasetVersion} (also shared.db)
 * references it, and the IMPORT service itself will depend on both once built
 * in Phase 3 - keeping the entities here avoids a shared-infra-depends-on-service
 * package cycle. See CLAUDE.md section 12.
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_mode", nullable = false, length = 20)
    private ImportMode importMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ImportStatus status = ImportStatus.PENDING;

    @Column(name = "total_rows")
    private Integer totalRows;

    @Column(name = "rows_staged", nullable = false)
    private int rowsStaged;

    @Column(name = "rows_rejected", nullable = false)
    private int rowsRejected;

    @Column(name = "initiated_by_admin_user_id")
    private Long initiatedByAdminUserId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public ImportMode getImportMode() {
        return importMode;
    }

    public void setImportMode(ImportMode importMode) {
        this.importMode = importMode;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public void setStatus(ImportStatus status) {
        this.status = status;
    }

    public Integer getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(Integer totalRows) {
        this.totalRows = totalRows;
    }

    public int getRowsStaged() {
        return rowsStaged;
    }

    public void setRowsStaged(int rowsStaged) {
        this.rowsStaged = rowsStaged;
    }

    public int getRowsRejected() {
        return rowsRejected;
    }

    public void setRowsRejected(int rowsRejected) {
        this.rowsRejected = rowsRejected;
    }

    public Long getInitiatedByAdminUserId() {
        return initiatedByAdminUserId;
    }

    public void setInitiatedByAdminUserId(Long initiatedByAdminUserId) {
        this.initiatedByAdminUserId = initiatedByAdminUserId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public enum ImportMode {
        APPEND,
        REPLACE
    }

    public enum ImportStatus {
        PENDING,
        STAGING,
        VALIDATING,
        NORMALIZING,
        AWAITING_CONFIRMATION,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}

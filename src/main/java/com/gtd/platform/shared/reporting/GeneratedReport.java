package com.gtd.platform.shared.reporting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.gtd.platform.shared.db.DatasetVersion;

/**
 * Maps to {@code generated_report} (CLAUDE.md section 7): immutable once
 * created, enforced by a database trigger (V8 migration) reusing
 * {@code fn_reject_table_mutation()} from {@code audit_log}. Deliberately no
 * relation to {@code fact_incident} - only {@link DatasetVersion}, so a
 * report stays listable/downloadable after the dataset version it was built
 * from is superseded. {@code filterSnapshot} maps {@code jsonb} the same way
 * {@link com.gtd.platform.shared.logging.AuditLog#getDetails()} does: a raw
 * JSON string via Hibernate 6's native JSON support.
 */
@Entity
@Table(name = "generated_report")
public class GeneratedReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_name", nullable = false, length = 255)
    private String reportName;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 10)
    private Format format;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.GENERATED;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_version_id", nullable = false)
    private DatasetVersion datasetVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_snapshot", nullable = false)
    private String filterSnapshot;

    @Column(name = "file_path", length = 1000)
    private String filePath;

    @Column(name = "generated_by_admin_user_id")
    private Long generatedByAdminUserId;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getReportName() {
        return reportName;
    }

    public void setReportName(String reportName) {
        this.reportName = reportName;
    }

    public Format getFormat() {
        return format;
    }

    public void setFormat(Format format) {
        this.format = format;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public DatasetVersion getDatasetVersion() {
        return datasetVersion;
    }

    public void setDatasetVersion(DatasetVersion datasetVersion) {
        this.datasetVersion = datasetVersion;
    }

    public String getFilterSnapshot() {
        return filterSnapshot;
    }

    public void setFilterSnapshot(String filterSnapshot) {
        this.filterSnapshot = filterSnapshot;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getGeneratedByAdminUserId() {
        return generatedByAdminUserId;
    }

    public void setGeneratedByAdminUserId(Long generatedByAdminUserId) {
        this.generatedByAdminUserId = generatedByAdminUserId;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public enum Format {
        PDF,
        CSV
    }

    public enum Status {
        GENERATED,
        FAILED
    }
}

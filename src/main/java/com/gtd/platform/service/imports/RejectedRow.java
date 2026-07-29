package com.gtd.platform.service.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps to {@code import_rejected_row} (V10 migration, Phase 3): one row per
 * staging row the IMPORT service declined to normalize into
 * {@code fact_incident}, or skipped as an in-batch duplicate under APPEND
 * mode. Feeds the future Data Quality Center - never delete these, even
 * after the owning {@code import_batch} completes.
 */
@Entity
@Table(name = "import_rejected_row")
public class RejectedRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_batch_id", nullable = false)
    private Long importBatchId;

    @Column(name = "staging_incident_id", nullable = false)
    private Long stagingIncidentId;

    @Column(name = "eventid")
    private String eventId;

    @Column(name = "rejection_reason", nullable = false, length = 60)
    private String rejectionReason;

    @Column(name = "rejection_detail")
    private String rejectionDetail;

    @Column(name = "rejected_at", nullable = false)
    private Instant rejectedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getImportBatchId() {
        return importBatchId;
    }

    public void setImportBatchId(Long importBatchId) {
        this.importBatchId = importBatchId;
    }

    public Long getStagingIncidentId() {
        return stagingIncidentId;
    }

    public void setStagingIncidentId(Long stagingIncidentId) {
        this.stagingIncidentId = stagingIncidentId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getRejectionDetail() {
        return rejectionDetail;
    }

    public void setRejectionDetail(String rejectionDetail) {
        this.rejectionDetail = rejectionDetail;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(Instant rejectedAt) {
        this.rejectedAt = rejectedAt;
    }
}

package com.gtd.platform.shared.logging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps to {@code audit_log} (CLAUDE.md section 7): append-only, enforced by
 * a database trigger (V7 migration), not by this entity. Callers must only
 * ever {@code save()} new rows - an update or delete attempt reaches the
 * database and is rejected there, by design (see V7's Javadoc-style comment
 * for the rationale).
 *
 * <p>{@code details} maps {@code jsonb} as a raw JSON string via Hibernate
 * 6's native {@code @JdbcTypeCode(SqlTypes.JSON)} support, rather than a
 * typed object graph - callers are expected to serialize/deserialize with
 * whatever JSON mapper (Jackson, already on the classpath) makes sense for
 * the specific audit event, since {@code audit_log} intentionally has no
 * fixed payload shape across the many action types it records (Phase 2).
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_admin_user_id")
    private Long actorAdminUserId;

    @Column(name = "action_type", nullable = false, length = 100)
    private String actionType;

    @Column(name = "entity_type", length = 100)
    private String entityType;

    @Column(name = "entity_id", length = 100)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    private String details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Long getActorAdminUserId() {
        return actorAdminUserId;
    }

    public void setActorAdminUserId(Long actorAdminUserId) {
        this.actorAdminUserId = actorAdminUserId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
}

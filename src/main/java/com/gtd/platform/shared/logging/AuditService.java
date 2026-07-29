package com.gtd.platform.shared.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The platform's one audit-logging mechanism (CLAUDE.md section 12): an
 * explicit service call, not an AOP aspect or interceptor. Every
 * state-changing action later phases add (config change, import trigger,
 * report generation - CONFIG/IMPORT/REPORT services, none of which exist
 * yet) should call {@link #record} directly from wherever the action
 * actually happens, the same way {@code shared.auth}'s login/logout wiring
 * does in Phase 2. An aspect keyed on some future {@code @Auditable}
 * annotation would need those future methods annotated anyway once built -
 * at that point it isn't materially less work than an explicit call, and
 * an explicit call is far easier to trace and unit test.
 *
 * <p>{@code audit_log} is append-only (a database trigger, not this class,
 * enforces that - see {@code V7__audit_log.sql}), so this service only ever
 * inserts.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an audit event tied to a specific entity.
     *
     * @param actionType        a short, stable action code (e.g. {@code "LOGIN_SUCCESS"})
     * @param actorAdminUserId  the acting admin_user's id, or {@code null} if unauthenticated/unknown (e.g. a failed login)
     * @param entityType        the type of entity affected, or {@code null}
     * @param entityId          the affected entity's id, or {@code null}
     * @param details           free-form structured detail, serialized to the {@code jsonb details} column; may be {@code null}
     * @param ipAddress         the originating client IP, or {@code null} if not applicable
     */
    public AuditLog record(String actionType, Long actorAdminUserId, String entityType, String entityId,
            Map<String, Object> details, String ipAddress) {
        AuditLog entry = new AuditLog();
        entry.setOccurredAt(Instant.now());
        entry.setActorAdminUserId(actorAdminUserId);
        entry.setActionType(actionType);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setDetails(toJson(details));
        entry.setIpAddress(ipAddress);
        return auditLogRepository.save(entry);
    }

    /** Convenience overload for events with no specific entity (e.g. login/logout). */
    public AuditLog record(String actionType, Long actorAdminUserId, String ipAddress) {
        return record(actionType, actorAdminUserId, null, null, null, ipAddress);
    }

    private String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize audit_log details to JSON", e);
        }
    }
}

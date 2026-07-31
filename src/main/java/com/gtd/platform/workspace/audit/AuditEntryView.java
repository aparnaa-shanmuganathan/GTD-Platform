package com.gtd.platform.workspace.audit;

import java.time.Instant;

/**
 * One {@code audit_log} row for display (Phase 10) - {@code actorUsername} is resolved via a
 * join to {@code admin_user} since {@code audit_log} itself only stores the actor's id.
 * Read-only by construction: this record has no corresponding write path anywhere in this
 * workspace, matching {@code audit_log}'s DB-trigger-enforced append-only/immutable nature
 * (V7 migration) - there is no edit/delete affordance anywhere in the Audit &amp; Activity Log UI.
 */
public record AuditEntryView(
        Long id,
        Instant occurredAt,
        String actorUsername,
        String actionType,
        String entityType,
        String entityId,
        String details,
        String ipAddress) {
}

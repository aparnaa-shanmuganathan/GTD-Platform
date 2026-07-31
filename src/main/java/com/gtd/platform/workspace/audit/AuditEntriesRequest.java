package com.gtd.platform.workspace.audit;

import java.time.LocalDate;

/**
 * {@code POST /api/audit/entries}'s filter shape - deliberately its own small request type, not
 * the shared canonical {@link com.gtd.platform.shared.api.AnalyticalFilterRequest}: audit
 * filtering is by actor/action/entity/date, none of which overlap that DTO's incident-dimension
 * fields (region, attack type, etc.), so reusing it would only add unused fields.
 *
 * <p>Every field is optional (null/blank means "no filter on this field"). {@code page} is
 * 0-based; {@code pageSize} is clamped server-side to a sane maximum.
 */
public record AuditEntriesRequest(
        String actor,
        String actionType,
        String entityType,
        LocalDate startDate,
        LocalDate endDate,
        Integer page,
        Integer pageSize) {
}

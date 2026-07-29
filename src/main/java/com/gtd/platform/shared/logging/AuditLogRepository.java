package com.gtd.platform.shared.logging;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only per {@code audit_log}'s database trigger (V7 migration) -
 * callers must only ever use {@link #save} to insert new rows. Deliberately
 * does not expose {@code delete}/{@code deleteById} beyond what
 * {@link JpaRepository} inherits; any attempt to use them will fail at the
 * database with the trigger's exception, not silently succeed.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}

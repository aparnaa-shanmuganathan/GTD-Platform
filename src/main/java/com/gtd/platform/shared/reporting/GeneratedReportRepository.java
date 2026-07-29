package com.gtd.platform.shared.reporting;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Immutable once created per {@code generated_report}'s database trigger
 * (V8 migration) - callers must only ever use {@link #save} to insert new
 * rows, never to update an existing one.
 */
public interface GeneratedReportRepository extends JpaRepository<GeneratedReport, Long> {
}

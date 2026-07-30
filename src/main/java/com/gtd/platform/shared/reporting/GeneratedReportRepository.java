package com.gtd.platform.shared.reporting;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Immutable once created per {@code generated_report}'s database trigger
 * (V8 migration) - callers must only ever use {@link #save} to insert new
 * rows, never to update an existing one.
 */
public interface GeneratedReportRepository extends JpaRepository<GeneratedReport, Long> {

    /**
     * The Report Workspace's history table, most recent first. An explicit
     * {@code JOIN FETCH} on the lazy {@code datasetVersion} association -
     * without it, {@code ReportSummary.from} (called after this repository
     * method's own transaction has already closed, since {@code spring.jpa.open-in-view}
     * is disabled platform-wide) would throw {@code LazyInitializationException}
     * the moment it calls {@code getDatasetVersion().getVersionLabel()}.
     */
    @Query("SELECT g FROM GeneratedReport g JOIN FETCH g.datasetVersion ORDER BY g.generatedAt DESC")
    List<GeneratedReport> findAllByOrderByGeneratedAtDesc();
}

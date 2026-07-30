package com.gtd.platform.service.report;

import com.gtd.platform.shared.reporting.GeneratedReport;
import java.time.Instant;

/**
 * REPORT's response shape for both "a report was just generated" and "the
 * report history list" - used by both the Report Workspace's own controller
 * and the Executive Workspace's "Generate executive report" button, so it
 * lives here (service-owned) rather than in {@code workspace.report}: two
 * workspace controllers depending on it is fine, but it living inside one
 * workspace package and being imported by another would violate CLAUDE.md
 * section 4's "never let a workspace package depend on another workspace
 * package" rule - the same reasoning {@code service.compare.CountryDashboardEntry}
 * follows in Phase 5. Deliberately not the raw {@link GeneratedReport} JPA
 * entity, so the API never leaks {@code filter_snapshot}'s full JSON or an
 * internal {@code file_path}.
 */
public record ReportSummary(
        Long id,
        String reportName,
        String format,
        String status,
        String datasetVersionLabel,
        Instant generatedAt
) {
    public static ReportSummary from(GeneratedReport report) {
        return new ReportSummary(
                report.getId(),
                report.getReportName(),
                report.getFormat().name(),
                report.getStatus().name(),
                report.getDatasetVersion().getVersionLabel(),
                report.getGeneratedAt());
    }
}

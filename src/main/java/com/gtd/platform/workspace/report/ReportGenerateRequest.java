package com.gtd.platform.workspace.report;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.reporting.GeneratedReport.Format;
import jakarta.validation.constraints.NotNull;

/**
 * @param filter          the canonical filter (CAC-UI-003) active in whichever workspace triggered generation; {@code null} means unfiltered
 * @param format          PDF or CSV
 * @param reportName      caller-supplied display name; if blank, {@link ReportController} generates a default from {@code sourceWorkspace}
 * @param sourceWorkspace a short label identifying which workspace's "Generate report" action this came from (e.g. {@code "Executive Workspace"})
 */
public record ReportGenerateRequest(
        AnalyticalFilterRequest filter,
        @NotNull Format format,
        String reportName,
        String sourceWorkspace
) {
}

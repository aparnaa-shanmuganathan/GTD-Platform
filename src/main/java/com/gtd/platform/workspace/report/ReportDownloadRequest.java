package com.gtd.platform.workspace.report;

import jakarta.validation.constraints.NotNull;

public record ReportDownloadRequest(@NotNull Long reportId) {
}

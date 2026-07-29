package com.gtd.platform.service.imports;

public record ImportConfirmResponse(Long importBatchId, String status, Long activeDatasetVersionId, String versionLabel) {
}

package com.gtd.platform.service.imports;

import jakarta.validation.constraints.NotNull;

public record ImportStatusRequest(@NotNull Long importBatchId) {
}

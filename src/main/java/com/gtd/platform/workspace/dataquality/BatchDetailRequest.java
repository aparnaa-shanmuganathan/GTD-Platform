package com.gtd.platform.workspace.dataquality;

import jakarta.validation.constraints.NotNull;

public record BatchDetailRequest(@NotNull Long importBatchId) {
}

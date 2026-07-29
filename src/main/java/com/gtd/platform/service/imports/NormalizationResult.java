package com.gtd.platform.service.imports;

/** The outcome of {@link RowNormalizer#normalize}: either a ready-to-write fact row, or a documented rejection. */
public sealed interface NormalizationResult {

    record Accepted(FactIncidentRecord factIncident) implements NormalizationResult {
    }

    record Rejected(RejectionReason reason, String detail) implements NormalizationResult {
    }
}

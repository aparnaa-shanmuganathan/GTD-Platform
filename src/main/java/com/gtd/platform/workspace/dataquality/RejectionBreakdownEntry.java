package com.gtd.platform.workspace.dataquality;

/** One rejection-reason bucket within a single import batch's detail view (Phase 10). */
public record RejectionBreakdownEntry(String rejectionReason, long count) {
}

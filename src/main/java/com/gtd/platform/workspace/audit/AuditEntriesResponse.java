package com.gtd.platform.workspace.audit;

import java.util.List;

public record AuditEntriesResponse(List<AuditEntryView> entries, long totalCount, int page, int pageSize) {
}

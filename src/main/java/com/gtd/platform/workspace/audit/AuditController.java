package com.gtd.platform.workspace.audit;

import com.gtd.platform.shared.api.ApiResponse;
import java.security.Principal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ADC-AUDIT-001: the Audit &amp; Activity Log Workspace (Phase 10) - a
 * queryable, filterable, read-only view over {@code audit_log} (Phase 2,
 * V7 migration). No dedicated service (a thin query layer over {@code
 * audit_log}/{@code admin_user}, not a new cross-cutting computation - same
 * reasoning Data Quality Center's controller already established).
 *
 * <p><strong>No edit/delete affordance exists anywhere in this controller
 * or its UI</strong> - only {@code GET}/{@code POST} read endpoints, matching
 * {@code audit_log}'s DB-trigger-enforced append-only/immutable nature
 * (any UPDATE/DELETE attempt would be rejected at the database regardless,
 * but this workspace never even offers the option).
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - {@code principal} is {@code null} for an anonymous visitor.
 */
@Controller
public class AuditController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;

    public AuditController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/workspace/audit")
    public String auditWorkspace(Model model, Principal principal) {
        model.addAttribute("username", principal != null ? principal.getName() : null);
        model.addAttribute("entries", queryEntries(new AuditEntriesRequest(null, null, null, null, null, 0, DEFAULT_PAGE_SIZE)));
        return "audit";
    }

    @PostMapping("/api/audit/entries")
    @ResponseBody
    public ApiResponse<AuditEntriesResponse> entries(@RequestBody(required = false) AuditEntriesRequest request) {
        AuditEntriesRequest effective = request != null ? request
                : new AuditEntriesRequest(null, null, null, null, null, 0, DEFAULT_PAGE_SIZE);
        return ApiResponse.success(queryEntries(effective));
    }

    private AuditEntriesResponse queryEntries(AuditEntriesRequest request) {
        int page = request.page() != null && request.page() >= 0 ? request.page() : 0;
        int pageSize = request.pageSize() != null ? Math.min(Math.max(request.pageSize(), 1), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (request.actor() != null && !request.actor().isBlank()) {
            conditions.add("u.username ILIKE ?");
            params.add("%" + request.actor().trim() + "%");
        }
        if (request.actionType() != null && !request.actionType().isBlank()) {
            conditions.add("a.action_type = ?");
            params.add(request.actionType().trim());
        }
        if (request.entityType() != null && !request.entityType().isBlank()) {
            conditions.add("a.entity_type = ?");
            params.add(request.entityType().trim());
        }
        if (request.startDate() != null) {
            conditions.add("a.occurred_at >= ?");
            params.add(Timestamp.valueOf(request.startDate().atStartOfDay()));
        }
        if (request.endDate() != null) {
            conditions.add("a.occurred_at < ?");
            params.add(Timestamp.valueOf(request.endDate().plusDays(1).atStartOfDay()));
        }

        String whereSql = conditions.isEmpty() ? "1=1" : String.join(" AND ", conditions);
        String fromSql = "FROM audit_log a LEFT JOIN admin_user u ON u.id = a.actor_admin_user_id WHERE " + whereSql;

        Long totalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) " + fromSql, Long.class, params.toArray());

        List<Object> listParams = new ArrayList<>(params);
        listParams.add(pageSize);
        listParams.add(page * pageSize);
        List<AuditEntryView> entries = jdbcTemplate.query(
                "SELECT a.id, a.occurred_at, u.username AS actor_username, a.action_type, a.entity_type, "
                        + "a.entity_id, a.details::text AS details, a.ip_address " + fromSql
                        + " ORDER BY a.occurred_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new AuditEntryView(
                        rs.getLong("id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("actor_username"),
                        rs.getString("action_type"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getString("details"),
                        rs.getString("ip_address")),
                listParams.toArray());

        return new AuditEntriesResponse(entries, totalCount != null ? totalCount : 0L, page, pageSize);
    }
}

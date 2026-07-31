package com.gtd.platform.workspace.administration;

import com.gtd.platform.service.config.PlatformConfigRequest;
import com.gtd.platform.service.config.PlatformConfigResponse;
import com.gtd.platform.service.config.PlatformConfigService;
import com.gtd.platform.service.imports.ImportBatchSummary;
import com.gtd.platform.service.imports.MaterializedViewRefreshService;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.ImportBatchRepository;
import com.gtd.platform.shared.logging.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ADC-ADMIN-001: the Administration Workspace (Phase 10) - the last of the
 * 15 workspaces named in CLAUDE.md section 4. Composes CONFIG ({@link
 * PlatformConfigService}) and the existing, already-authenticated IMPORT
 * endpoints ({@code ImportController}, unchanged) into one operational
 * screen; adds a manual materialized-view refresh action as the required
 * escape hatch for CONFIG's auto-refresh-off case.
 *
 * <p><strong>Access model, precisely (CLAUDE.md section 12):</strong> {@code
 * GET /workspace/administration} and {@code POST /api/config} (viewing
 * current settings) are {@code permitAll()} - viewing never changes state.
 * Only {@link #saveConfig} and {@link #refreshViews} are {@code
 * authenticated()} (see {@code SecurityConfig}), the same gate {@code
 * /api/import/**} already sits behind - this controller adds no new
 * authentication logic itself, it relies entirely on {@code SecurityConfig}'s
 * request-matcher rules.
 */
@Controller
public class AdministrationController {

    private final PlatformConfigService platformConfigService;
    private final MaterializedViewRefreshService materializedViewRefreshService;
    private final ImportBatchRepository importBatchRepository;
    private final AdminUserRepository adminUserRepository;
    private final AuditService auditService;

    public AdministrationController(PlatformConfigService platformConfigService,
            MaterializedViewRefreshService materializedViewRefreshService, ImportBatchRepository importBatchRepository,
            AdminUserRepository adminUserRepository, AuditService auditService) {
        this.platformConfigService = platformConfigService;
        this.materializedViewRefreshService = materializedViewRefreshService;
        this.importBatchRepository = importBatchRepository;
        this.adminUserRepository = adminUserRepository;
        this.auditService = auditService;
    }

    @GetMapping("/workspace/administration")
    public String administrationWorkspace(Model model, Principal principal) {
        model.addAttribute("username", principal != null ? principal.getName() : null);
        model.addAttribute("config", platformConfigService.getCurrent());
        model.addAttribute("recentBatches", recentBatchSummaries());
        return "administration";
    }

    /** Public - viewing current settings never changes state. */
    @PostMapping("/api/config")
    @ResponseBody
    public ApiResponse<PlatformConfigResponse> config() {
        return ApiResponse.success(platformConfigService.getCurrent());
    }

    /** Authenticated (CLAUDE.md section 12) - the one CONFIG action that actually changes platform state. */
    @PostMapping("/api/config/save")
    @ResponseBody
    public ApiResponse<PlatformConfigResponse> saveConfig(@Valid @RequestBody PlatformConfigRequest request,
            Authentication authentication, HttpServletRequest servletRequest) {
        Long adminUserId = resolveAdminUserId(authentication);
        PlatformConfigResponse saved = platformConfigService.save(request, adminUserId, servletRequest.getRemoteAddr());
        return ApiResponse.success(saved);
    }

    /**
     * Authenticated (CLAUDE.md section 12) - the manual escape hatch for CONFIG's
     * materialized-view-auto-refresh-off case, so disabling that toggle never strands the
     * platform in a permanently stale state.
     */
    @PostMapping("/api/administration/refresh-views")
    @ResponseBody
    public ApiResponse<Void> refreshViews(Authentication authentication, HttpServletRequest servletRequest) {
        Long adminUserId = resolveAdminUserId(authentication);
        materializedViewRefreshService.refreshAll();
        auditService.record("MATERIALIZED_VIEW_MANUAL_REFRESH", adminUserId, servletRequest.getRemoteAddr());
        return ApiResponse.success(null);
    }

    /** Public - read-only, powers the import-history panel's own refresh button after an upload completes. */
    @PostMapping("/api/administration/import-batches")
    @ResponseBody
    public ApiResponse<List<ImportBatchSummary>> importBatches() {
        return ApiResponse.success(recentBatchSummaries());
    }

    private List<ImportBatchSummary> recentBatchSummaries() {
        return importBatchRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(ImportBatchSummary::from)
                .toList();
    }

    private Long resolveAdminUserId(Authentication authentication) {
        return adminUserRepository.findByUsername(authentication.getName())
                .map(AdminUser::getId)
                .orElse(null);
    }
}

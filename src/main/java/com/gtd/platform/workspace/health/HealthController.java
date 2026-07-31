package com.gtd.platform.workspace.health;

import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.MaterializedViewStatus;
import com.gtd.platform.shared.db.MaterializedViewStatusRepository;
import com.gtd.platform.workspace.health.HealthCheckResponse.ActiveDatasetVersionStatus;
import com.gtd.platform.workspace.health.HealthCheckResponse.DbConnectionStatus;
import com.gtd.platform.workspace.health.HealthCheckResponse.MaterializedViewFreshness;
import com.gtd.platform.workspace.health.HealthCheckResponse.OfflineVerification;
import java.security.Principal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ADC-HEALTH-001: the System Health Dashboard (Phase 10) - DB connection
 * status, active dataset version, materialized-view freshness (from {@code
 * materialized_view_status}, populated by {@code MaterializedViewRefreshService}),
 * and the offline-verification indicator ({@link OfflineAssetScanner} -
 * see its Javadoc for why this is a real static asset scan, not a
 * hardcoded "passed" or a live network probe). No dedicated cross-cutting
 * service - same "thin controller over existing repositories/one small
 * helper class" reasoning Data Quality Center and Audit &amp; Activity Log
 * already established.
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - every check here is read-only, so nothing requires authentication;
 * {@code principal} is {@code null} for an anonymous visitor.
 */
@Controller
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final DatasetVersionRepository datasetVersionRepository;
    private final MaterializedViewStatusRepository materializedViewStatusRepository;

    public HealthController(JdbcTemplate jdbcTemplate, DatasetVersionRepository datasetVersionRepository,
            MaterializedViewStatusRepository materializedViewStatusRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.datasetVersionRepository = datasetVersionRepository;
        this.materializedViewStatusRepository = materializedViewStatusRepository;
    }

    @GetMapping("/workspace/health")
    public String healthWorkspace(Model model, Principal principal) {
        model.addAttribute("username", principal != null ? principal.getName() : null);
        model.addAttribute("health", runCheck());
        return "health";
    }

    @PostMapping("/api/health/check")
    @ResponseBody
    public ApiResponse<HealthCheckResponse> check() {
        return ApiResponse.success(runCheck());
    }

    private HealthCheckResponse runCheck() {
        return new HealthCheckResponse(checkDatabase(), checkActiveDatasetVersion(),
                checkMaterializedViews(), checkOfflineVerification(), Instant.now());
    }

    private DbConnectionStatus checkDatabase() {
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return new DbConnectionStatus(true, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return new DbConnectionStatus(false, System.currentTimeMillis() - start);
        }
    }

    private ActiveDatasetVersionStatus checkActiveDatasetVersion() {
        return datasetVersionRepository.findByActiveTrue()
                .map(v -> new ActiveDatasetVersionStatus(v.getId(), v.getVersionLabel(), v.getActivatedAt(), v.getRowCount()))
                .orElse(null);
    }

    private List<MaterializedViewFreshness> checkMaterializedViews() {
        return materializedViewStatusRepository.findAll().stream()
                .sorted(Comparator.comparing(MaterializedViewStatus::getViewName))
                .map(v -> new MaterializedViewFreshness(v.getViewName(), v.getLastRefreshedAt(),
                        v.getLastRefreshStatus().name(), v.getLastRefreshDurationMs(), v.getRowCount(),
                        v.getLastRefreshStatus() == MaterializedViewStatus.RefreshStatus.SUCCESS))
                .toList();
    }

    private OfflineVerification checkOfflineVerification() {
        OfflineAssetScanner.ScanResult result = OfflineAssetScanner.scanBundledAssets();
        return new OfflineVerification(result.passed(), result.resourcesScanned(), result.findings());
    }
}

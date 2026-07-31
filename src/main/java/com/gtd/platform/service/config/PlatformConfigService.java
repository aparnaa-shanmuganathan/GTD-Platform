package com.gtd.platform.service.config;

import com.gtd.platform.shared.db.PlatformConfig;
import com.gtd.platform.shared.db.PlatformConfigRepository;
import com.gtd.platform.shared.exception.ApiException;
import com.gtd.platform.shared.exception.ErrorCode;
import com.gtd.platform.shared.logging.AuditService;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CONFIG (CLAUDE.md section 4): the consolidated platform configuration
 * service, replacing what earlier phases left as scattered hardcoded
 * defaults - see CLAUDE.md section 12's "Phase 10 decisions" for exactly
 * which settings this service owns (and, just as importantly, which
 * earlier hardcoded defaults were deliberately left alone rather than
 * folded in here).
 *
 * <p>Every setting this service exposes genuinely changes platform
 * behavior when saved - none is a stored-but-ignored flag:
 * <ul>
 *   <li>{@code sessionTimeoutMinutes} is pushed into the live, autoconfigured
 *       {@link JdbcIndexedSessionRepository} bean via
 *       {@code setDefaultMaxInactiveInterval} - both at startup ({@link
 *       #applyPersistedSessionTimeout()}) and on every save. New sessions
 *       created after a save pick up the new timeout immediately; already-open
 *       sessions keep whatever expiry they were given at creation - normal
 *       session semantics, not a gap.</li>
 *   <li>{@code defaultImportMode} is read by {@code ImportController.upload}
 *       when the request omits {@code mode} - a real server-side fallback.</li>
 *   <li>{@code materializedViewAutoRefresh} is read by {@code
 *       ImportConfirmationService} to decide whether to auto-refresh the
 *       {@code mv_*_year_agg} views after a confirmed import; the
 *       Administration Workspace's manual "Refresh views now" action
 *       (calling {@code MaterializedViewRefreshService} directly) is the
 *       required escape hatch for when this is off.</li>
 *   <li>{@code notifyOnImportCompletion} is read client-side by {@code
 *       administration.js}'s own import-status polling to decide whether to
 *       show a completion toast - there is no server-push/email/webhook
 *       mechanism anywhere in this platform, so this is the honest, real
 *       shape the setting can take (confirmed with the user before
 *       implementation).</li>
 * </ul>
 */
@Service
public class PlatformConfigService {

    private final PlatformConfigRepository platformConfigRepository;
    private final AuditService auditService;
    private final JdbcIndexedSessionRepository sessionRepository;

    public PlatformConfigService(PlatformConfigRepository platformConfigRepository, AuditService auditService,
            JdbcIndexedSessionRepository sessionRepository) {
        this.platformConfigRepository = platformConfigRepository;
        this.auditService = auditService;
        this.sessionRepository = sessionRepository;
    }

    /** Applies the persisted session timeout to the live session repository on startup - the migration's seeded row guarantees one exists. */
    @PostConstruct
    public void applyPersistedSessionTimeout() {
        applySessionTimeout(getCurrentEntity());
    }

    public PlatformConfigResponse getCurrent() {
        return toResponse(getCurrentEntity());
    }

    @Transactional
    public PlatformConfigResponse save(PlatformConfigRequest request, Long adminUserId, String ipAddress) {
        if (request.sessionTimeoutMinutes() < 5 || request.sessionTimeoutMinutes() > 1440) {
            throw new ApiException(ErrorCode.ERR_CONFIG_001, "sessionTimeoutMinutes must be between 5 and 1440");
        }

        PlatformConfig entity = getCurrentEntity();
        Map<String, Object> changes = new HashMap<>();
        recordIfChanged(changes, "sessionTimeoutMinutes", entity.getSessionTimeoutMinutes(), request.sessionTimeoutMinutes());
        recordIfChanged(changes, "defaultImportMode", entity.getDefaultImportMode(), request.defaultImportMode());
        recordIfChanged(changes, "materializedViewAutoRefresh", entity.isMaterializedViewAutoRefresh(), request.materializedViewAutoRefresh());
        recordIfChanged(changes, "notifyOnImportCompletion", entity.isNotifyOnImportCompletion(), request.notifyOnImportCompletion());

        entity.setSessionTimeoutMinutes(request.sessionTimeoutMinutes());
        entity.setDefaultImportMode(request.defaultImportMode());
        entity.setMaterializedViewAutoRefresh(request.materializedViewAutoRefresh());
        entity.setNotifyOnImportCompletion(request.notifyOnImportCompletion());
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedByAdminUserId(adminUserId);
        platformConfigRepository.save(entity);

        applySessionTimeout(entity);
        auditService.record("CONFIG_UPDATED", adminUserId, "platform_config", "1", changes, ipAddress);

        return toResponse(entity);
    }

    private void recordIfChanged(Map<String, Object> changes, String field, Object oldValue, Object newValue) {
        if (!oldValue.equals(newValue)) {
            changes.put(field, Map.of("from", oldValue, "to", newValue));
        }
    }

    private void applySessionTimeout(PlatformConfig entity) {
        sessionRepository.setDefaultMaxInactiveInterval(Duration.ofMinutes(entity.getSessionTimeoutMinutes()));
    }

    private PlatformConfig getCurrentEntity() {
        return platformConfigRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException(
                        "platform_config's seeded row (id=1) is missing - V11 migration should guarantee it exists"));
    }

    private PlatformConfigResponse toResponse(PlatformConfig entity) {
        return new PlatformConfigResponse(entity.getSessionTimeoutMinutes(), entity.getDefaultImportMode(),
                entity.isMaterializedViewAutoRefresh(), entity.isNotifyOnImportCompletion(), entity.getUpdatedAt());
    }
}

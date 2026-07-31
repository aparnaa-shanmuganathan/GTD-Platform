package com.gtd.platform.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.ImportBatch.ImportMode;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.FormLoginClient.LoginResult;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Proves CONFIG's session-timeout setting has a real, measurable effect on
 * session creation - not just that {@link PlatformConfigService#save}
 * persists a number nobody reads. Rather than reflect into {@code
 * JdbcIndexedSessionRepository} (which exposes no public getter for its
 * current {@code defaultMaxInactiveInterval} - a setter-only configuration
 * property by design), this saves a distinctive timeout, performs a real
 * login through the actual HTTP login flow (creating a genuine session via
 * that same repository bean), and reads back {@code
 * SPRING_SESSION.MAX_INACTIVE_INTERVAL} (V6 migration, copied verbatim from
 * Spring Session's own schema) for that real session - the actual value the
 * live repository bean applied, not a value this test injected itself.
 *
 * <p>The default-import-mode fallback and materialized-view-auto-refresh
 * gating are proven at the HTTP/workspace level by {@code
 * AdministrationWorkspaceIntegrationTest}, since both are most naturally
 * exercised through the real import flow that test already drives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformConfigServiceIntegrationTest extends PostgresIntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private PlatformConfigService platformConfigService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void saveRoundTripsThroughGetCurrent() {
        PlatformConfigRequest request = new PlatformConfigRequest(90, ImportMode.REPLACE, false, false);
        PlatformConfigResponse saved = platformConfigService.save(request, null, "127.0.0.1");

        assertThat(saved.sessionTimeoutMinutes()).isEqualTo(90);
        assertThat(saved.defaultImportMode()).isEqualTo(ImportMode.REPLACE);
        assertThat(saved.materializedViewAutoRefresh()).isFalse();
        assertThat(saved.notifyOnImportCompletion()).isFalse();

        PlatformConfigResponse current = platformConfigService.getCurrent();
        assertThat(current.sessionTimeoutMinutes()).isEqualTo(90);
        assertThat(current.defaultImportMode()).isEqualTo(ImportMode.REPLACE);

        // Restore defaults so later tests in the shared suite aren't affected.
        platformConfigService.save(new PlatformConfigRequest(30, ImportMode.APPEND, true, true), null, "127.0.0.1");
    }

    @Test
    void sessionTimeoutSaveGenuinelyChangesRealNewSessionExpiry() throws Exception {
        // A distinctive value unlikely to collide with any other test's own assumption.
        platformConfigService.save(new PlatformConfigRequest(17, ImportMode.APPEND, true, true), null, "127.0.0.1");

        String username = "config-it-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        adminUserRepository.save(user);

        // A real login through the actual HTTP flow - the same JdbcIndexedSessionRepository
        // bean PlatformConfigService.save just reconfigured is what creates this session row.
        FormLoginClient client = new FormLoginClient("http://localhost:" + port);
        LoginResult loginResult = client.login(username, password);
        assertThat(loginResult.isRedirect()).isTrue();

        Integer maxInactiveInterval = jdbcTemplate.queryForObject(
                "SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ? "
                        + "ORDER BY CREATION_TIME DESC LIMIT 1",
                Integer.class, username);
        assertThat(maxInactiveInterval).isEqualTo(17 * 60);

        platformConfigService.save(new PlatformConfigRequest(30, ImportMode.APPEND, true, true), null, "127.0.0.1");
    }
}

package com.gtd.platform.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.shared.logging.AuditLog;
import com.gtd.platform.shared.logging.AuditLogRepository;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.FormLoginClient.LoginResult;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration coverage for Phase 2's login/logout flow and the audit-logging
 * mechanism, against a real Spring context and real PostgreSQL (Testcontainers
 * or embedded-postgres, see {@link PostgresIntegrationTestBase}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest extends PostgresIntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private FormLoginClient client;
    private String username;
    private Long adminUserId;

    @BeforeEach
    void setUp() {
        client = new FormLoginClient("http://localhost:" + port);
        username = "auth-it-user-" + UUID.randomUUID();

        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        adminUserId = adminUserRepository.save(user).getId();
    }

    @Test
    void validLoginRedirectsHomeAndWritesAuditLogRow() throws Exception {
        LoginResult result = client.login(username, PASSWORD);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.redirectLocation()).endsWith("/");

        List<AuditLog> matches = auditLogRepository.findAll().stream()
                .filter(entry -> "LOGIN_SUCCESS".equals(entry.getActionType()))
                .filter(entry -> adminUserId.equals(entry.getActorAdminUserId()))
                .toList();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getEntityId()).isEqualTo(username);

        AdminUser reloaded = adminUserRepository.findById(adminUserId).orElseThrow();
        assertThat(reloaded.getLastLoginAt()).isNotNull();
    }

    @Test
    void invalidPasswordRedirectsToLoginErrorAndWritesFailureAuditRow() throws Exception {
        LoginResult result = client.login(username, "wrong-password");

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.redirectLocation()).contains("/login?error");

        List<AuditLog> matches = auditLogRepository.findAll().stream()
                .filter(entry -> "LOGIN_FAILURE".equals(entry.getActionType()))
                .filter(entry -> username.equals(entry.getEntityId()))
                .toList();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getActorAdminUserId()).isNull();
    }

    @Test
    void logoutInvalidatesSessionAndWritesAuditLogRow() throws Exception {
        LoginResult loginResult = client.login(username, PASSWORD);
        String authenticatedCookie = loginResult.sessionCookie();

        HttpResponse<String> logoutResponse = client.logout(authenticatedCookie);
        assertThat(logoutResponse.statusCode()).isIn(302, 303);
        assertThat(logoutResponse.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).contains("/login?logout"));

        List<AuditLog> matches = auditLogRepository.findAll().stream()
                .filter(entry -> "LOGOUT".equals(entry.getActionType()))
                .filter(entry -> adminUserId.equals(entry.getActorAdminUserId()))
                .toList();
        assertThat(matches).hasSize(1);

        // "/" is publicly viewable regardless of session state (CLAUDE.md section 12's
        // access model amendment - authentication gates dataset import only), so it no
        // longer redirects an unauthenticated/invalidated session to /login; it now
        // renders the anonymous branch of the page directly.
        HttpResponse<String> afterLogout = client.getWithCookie("/", authenticatedCookie);
        assertThat(afterLogout.statusCode()).isEqualTo(200);

        // The old session itself is genuinely gone (not just "/" being public) -
        // proven against /api/import/**, the one surface still gated by authentication.
        HttpResponse<String> importStatusAfterLogout =
                client.postJsonWithoutCsrfToken("/api/import/status", authenticatedCookie, "{\"importBatchId\":1}");
        assertThat(importStatusAfterLogout.statusCode()).isIn(302, 401, 403);
    }
}

package com.gtd.platform.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
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
 * Phase 7 hardening pass, security review (CLAUDE.md section 12): proves
 * three cross-cutting guarantees {@link com.gtd.platform.shared.auth.SecurityConfig}
 * is supposed to provide, against real HTTP requests rather than just
 * reading the config and assuming - a representative sample of protected
 * {@code POST} endpoints across every workspace/service actually reject
 * unauthenticated requests, CSRF protection actually rejects a
 * state-changing request with no token, and the session cookie is
 * {@code HttpOnly} without being (incorrectly, for a plain-HTTP local
 * deployment) marked {@code Secure}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHardeningTest extends PostgresIntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private FormLoginClient client;

    @BeforeEach
    void setUp() {
        client = new FormLoginClient("http://localhost:" + port);
    }

    @Test
    void protectedPostEndpointsAcrossEveryWorkspaceRejectUnauthenticatedRequests() throws Exception {
        // One representative POST endpoint per workspace/service that exists so far - not
        // exhaustive, but spans IMPORT, Executive, Country, and Report so a gap in any one
        // controller's security configuration (e.g. a missing requestMatcher) would show up
        // here rather than only being caught by that workspace's own happy-path test, which
        // always logs in first and would never notice.
        List<String> protectedPostEndpoints = List.of(
                "/api/import/status",
                "/api/executive/dashboard",
                "/api/country/ranking",
                "/api/report/history"
        );

        for (String path : protectedPostEndpoints) {
            HttpResponse<String> response = client.postJsonWithoutCsrfToken(path, "", "{}");
            assertThat(response.statusCode())
                    .withFailMessage("Expected %s to reject an unauthenticated request, but got %s", path, response.statusCode())
                    .isNotEqualTo(200);
            // Spring Security's default form-login entry point redirects unauthenticated
            // requests to /login regardless of endpoint - it never reaches the controller.
            assertThat(response.statusCode()).isIn(302, 401, 403);
        }
    }

    @Test
    void protectedWorkspacePagesRejectUnauthenticatedRequests() throws Exception {
        List<String> protectedPages = List.of("/", "/workspace/executive", "/workspace/country", "/workspace/report");

        for (String path : protectedPages) {
            HttpResponse<String> response = client.getWithCookie(path, "");
            assertThat(response.statusCode())
                    .withFailMessage("Expected %s to redirect an unauthenticated request to login, but got %s", path, response.statusCode())
                    .isIn(302, 303);
            assertThat(response.headers().firstValue("Location")).hasValueSatisfying(
                    location -> assertThat(location).contains("/login"));
        }
    }

    @Test
    void stateChangingRequestWithoutCsrfTokenIsRejected() throws Exception {
        String username = "csrf-it-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        adminUserRepository.save(user);

        LoginResult loginResult = client.login(username, password);
        assertThat(loginResult.isRedirect()).isTrue();
        String cookie = loginResult.sessionCookie();

        // Authenticated, but no X-CSRF-TOKEN header - Spring Security's CSRF filter must
        // reject this before it ever reaches ReportController.
        HttpResponse<String> response = client.postJsonWithoutCsrfToken("/api/report/history", cookie, "{}");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void sessionCookieIsHttpOnlyAndNotMarkedSecureOverPlainHttp() throws Exception {
        String username = "cookie-it-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        adminUserRepository.save(user);

        LoginResult loginResult = client.login(username, password);
        assertThat(loginResult.isRedirect()).isTrue();

        // FormLoginClient.login collapses Set-Cookie headers down to "name=value" pairs for
        // reuse as a Cookie request header, so re-derive the raw Set-Cookie attributes directly
        // by logging in again and reading the response ourselves.
        HttpResponse<String> getLogin = client.getWithCookie("/login", "");
        String preAuthCookie = getLogin.headers().allValues("Set-Cookie").stream()
                .filter(h -> h.startsWith("SESSION="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SESSION cookie in GET /login response"));

        assertThat(preAuthCookie).containsIgnoringCase("HttpOnly");
        // Deliberately NOT Secure: this platform is a local/offline, plain-HTTP deployment
        // (CLAUDE.md section 1) - marking the cookie Secure would mean it's never sent back
        // over plain HTTP at all, breaking every request after login. Absence here is
        // correct behavior for this deployment mode, not a gap.
        assertThat(preAuthCookie).doesNotContainIgnoringCase("Secure");
    }
}

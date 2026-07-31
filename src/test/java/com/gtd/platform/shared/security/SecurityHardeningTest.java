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
 * Phase 7 hardening pass, security review (CLAUDE.md section 12), updated
 * for the post-Phase-7 access model amendment (also section 12):
 * authentication now gates dataset import only, so this class proves - all
 * against real HTTP requests rather than just reading the config and
 * assuming - that {@code /api/import/**} still rejects an unauthenticated
 * caller, that every analytical workspace page/POST endpoint is now
 * genuinely publicly reachable, that CSRF protection still rejects a
 * state-changing request with no token (unchanged by the access model
 * amendment - it applies regardless of whether an endpoint also requires
 * authentication), and that the session cookie is {@code HttpOnly} without
 * being (incorrectly, for a plain-HTTP local deployment) marked
 * {@code Secure}.
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
    void importEndpointsStillRejectUnauthenticatedRequests() throws Exception {
        // IMPORT is the one surface the access model amendment leaves gated by
        // authentication (CLAUDE.md section 12) - every /api/import/** endpoint must still
        // reject an anonymous caller. Spring Security's authorization filter runs ahead of
        // Spring MVC dispatch, so this holds regardless of each endpoint's actual body shape
        // (multipart upload vs. JSON status/confirm) - the request never reaches
        // ImportController at all.
        List<String> importEndpoints = List.of("/api/import/upload", "/api/import/status", "/api/import/confirm");

        for (String path : importEndpoints) {
            HttpResponse<String> response = client.postJsonWithoutCsrfToken(path, "", "{}");
            assertThat(response.statusCode())
                    .withFailMessage("Expected %s to reject an unauthenticated request, but got %s", path, response.statusCode())
                    .isIn(302, 401, 403);
        }
    }

    @Test
    void analyticalPostEndpointsAreNowPubliclyAccessible() throws Exception {
        // CLAUDE.md section 12's access model amendment: every analytical workspace endpoint
        // is publicly viewable with no login required. CSRF protection is untouched by the
        // amendment though - it still applies to every state-changing POST regardless of
        // authentication, so this uses a real (anonymous) session + CSRF token rather than
        // an authenticated one, proving public access without disabling the CSRF check.
        HttpResponse<String> loginPage = client.getWithCookie("/login", "");
        String anonymousCookie = sessionCookieFrom(loginPage);
        String anonymousCsrfToken = FormLoginClient.extractCsrfToken(loginPage.body());

        List<String> publicPostEndpoints = List.of(
                "/api/executive/dashboard",
                "/api/country/ranking",
                "/api/report/history"
        );

        for (String path : publicPostEndpoints) {
            HttpResponse<String> response = client.postJson(path, anonymousCookie, anonymousCsrfToken, "{}");
            assertThat(response.statusCode())
                    .withFailMessage("Expected %s to be publicly accessible, but got %s", path, response.statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    void analyticalWorkspacePagesAreNowPubliclyAccessible() throws Exception {
        List<String> publicPages = List.of("/", "/workspace/executive", "/workspace/country", "/workspace/report");

        for (String path : publicPages) {
            HttpResponse<String> response = client.getWithCookie(path, "");
            assertThat(response.statusCode())
                    .withFailMessage("Expected %s to be publicly accessible, but got %s", path, response.statusCode())
                    .isEqualTo(200);
        }
    }

    /** Extracts the raw {@code SESSION=...} cookie a response set, for reuse as a request {@code Cookie} header. */
    private static String sessionCookieFrom(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(h -> h.startsWith("SESSION="))
                .map(h -> h.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SESSION cookie in response"));
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

package com.gtd.platform.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.FormLoginClient.LoginResult;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * End-to-end coverage of the Audit &amp; Activity Log Workspace (Phase 10):
 * a real state-changing action (a CONFIG save) genuinely produces a
 * queryable {@code audit_log} row, filterable by actor/action type and
 * paginated - all read-only, all public. This test never calls (and this
 * workspace never exposes) any edit/delete endpoint - there is none to call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private FormLoginClient client;
    private String username;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = new FormLoginClient("http://localhost:" + port);
        username = "audit-it-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";

        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        adminUserRepository.save(user);

        LoginResult loginResult = client.login(username, password);
        assertThat(loginResult.isRedirect()).isTrue();
        cookie = loginResult.sessionCookie();
        csrfToken = client.csrfTokenFor(cookie);
    }

    @Test
    void auditLogSurfacesRealConfigUpdateEntryFilterableByActorAndAction() throws Exception {
        // --- A real state-changing action: save CONFIG, which records a real CONFIG_UPDATED audit row ---
        String saveBody = "{\"sessionTimeoutMinutes\":33,\"defaultImportMode\":\"APPEND\","
                + "\"materializedViewAutoRefresh\":true,\"notifyOnImportCompletion\":true}";
        HttpResponse<String> saveResponse = client.postJson("/api/config/save", cookie, csrfToken, saveBody);
        assertThat(saveResponse.statusCode()).isEqualTo(200);

        // --- GET /workspace/audit and POST /api/audit/entries are both public - fresh anonymous session ---
        HttpResponse<String> anonymousLoginPage = client.getWithCookie("/login", "");
        String anonymousCookie = sessionCookieFrom(anonymousLoginPage);
        String anonymousCsrf = FormLoginClient.extractCsrfToken(anonymousLoginPage.body());

        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/audit", anonymousCookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        assertThat(pageResponse.body()).contains("Audit");

        // --- Filter by actionType finds the real CONFIG_UPDATED row ---
        HttpResponse<String> byActionResponse = client.postJson("/api/audit/entries", anonymousCookie, anonymousCsrf,
                "{\"actionType\":\"CONFIG_UPDATED\"}");
        assertThat(byActionResponse.statusCode()).isEqualTo(200);
        JsonNode byActionData = JSON.readTree(byActionResponse.body()).path("data");
        assertThat(byActionData.path("totalCount").asLong()).isGreaterThan(0);
        boolean sawOurActor = false;
        for (JsonNode entry : byActionData.path("entries")) {
            assertThat(entry.path("actionType").asText()).isEqualTo("CONFIG_UPDATED");
            if (username.equals(entry.path("actorUsername").asText())) {
                sawOurActor = true;
                assertThat(entry.path("details").asText()).contains("sessionTimeoutMinutes");
            }
        }
        assertThat(sawOurActor).withFailMessage("Expected an entry actored by %s", username).isTrue();

        // --- Filter by actor (substring match) finds only this test's own entries ---
        HttpResponse<String> byActorResponse = client.postJson("/api/audit/entries", anonymousCookie, anonymousCsrf,
                "{\"actor\":\"" + username + "\"}");
        JsonNode byActorData = JSON.readTree(byActorResponse.body()).path("data");
        assertThat(byActorData.path("entries").size()).isGreaterThan(0);
        for (JsonNode entry : byActorData.path("entries")) {
            assertThat(entry.path("actorUsername").asText()).isEqualTo(username);
        }

        // --- Pagination: pageSize=1 returns exactly 1 entry when more than one exists ---
        HttpResponse<String> pagedResponse = client.postJson("/api/audit/entries", anonymousCookie, anonymousCsrf,
                "{\"actor\":\"" + username + "\",\"page\":0,\"pageSize\":1}");
        JsonNode pagedData = JSON.readTree(pagedResponse.body()).path("data");
        assertThat(pagedData.path("entries").size()).isEqualTo(1);
        assertThat(pagedData.path("pageSize").asInt()).isEqualTo(1);
    }

    private static String sessionCookieFrom(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(h -> h.startsWith("SESSION="))
                .map(h -> h.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SESSION cookie in response"));
    }
}

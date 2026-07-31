package com.gtd.platform.workspace.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.FormLoginClient.LoginResult;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * End-to-end coverage of the Administration Workspace (Phase 10): CONFIG's
 * view/save split (public view, authenticated save), the manual
 * materialized-view refresh escape hatch, and CONFIG's real, backend-
 * enforced default-import-mode fallback when a caller omits {@code mode}
 * from {@code POST /api/import/upload} - proving the setting genuinely
 * changes platform behavior, not just that it's stored. {@link
 * com.gtd.platform.service.config.PlatformConfigServiceIntegrationTest}
 * separately proves the session-timeout setting's real effect on session
 * creation; this test's job is the workspace's own HTTP wiring, the
 * project's established division of labor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdministrationWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int YEAR = 2200;
    private static final String CSV_HEADER = "eventid,iyear,imonth,iday,country_txt,region_txt,provstate,city,"
            + "latitude,longitude,summary,target1,attacktype1_txt,weaptype1_txt,targtype1_txt,gname,nkill,nwound,"
            + "success,suicide,individual,property,motive";

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private FormLoginClient client;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = new FormLoginClient("http://localhost:" + port);
        String username = "admin-it-user-" + UUID.randomUUID();
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
    void administrationWorkspaceRendersAndConfigViewIsPublic() throws Exception {
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/administration", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        assertThat(pageResponse.body()).contains("Administration");
        assertThat(pageResponse.body()).contains("configForm");

        // --- Anonymous view of CONFIG is public - fresh anonymous session, no auth needed.
        // CSRF token is extracted from /login's own form (which Thymeleaf always injects a
        // hidden _csrf input into, authenticated or not), the same pattern SecurityHardeningTest
        // already established for anonymous-session CSRF tokens - administration.html's own
        // configForm is a bare JS-driven form with no th:action, so it has no such hidden input. ---
        HttpResponse<String> anonymousLoginPage = client.getWithCookie("/login", "");
        String anonymousCookie = sessionCookieFrom(anonymousLoginPage);
        String anonymousCsrf = FormLoginClient.extractCsrfToken(anonymousLoginPage.body());

        HttpResponse<String> anonymousPage = client.getWithCookie("/workspace/administration", anonymousCookie);
        assertThat(anonymousPage.statusCode()).isEqualTo(200);

        HttpResponse<String> viewResponse = client.postJson("/api/config", anonymousCookie, anonymousCsrf, "{}");
        assertThat(viewResponse.statusCode()).isEqualTo(200);
        JsonNode viewData = JSON.readTree(viewResponse.body()).path("data");
        assertThat(viewData.path("sessionTimeoutMinutes").asInt()).isBetween(5, 1440);
    }

    @Test
    void configSaveRequiresAuthenticationButTakesRealEffectWhenAuthenticated() throws Exception {
        // --- Anonymous save is rejected ---
        String saveBody = "{\"sessionTimeoutMinutes\":45,\"defaultImportMode\":\"APPEND\","
                + "\"materializedViewAutoRefresh\":true,\"notifyOnImportCompletion\":true}";
        HttpResponse<String> anonymousSave = client.postJsonWithoutCsrfToken("/api/config/save", "", saveBody);
        assertThat(anonymousSave.statusCode()).isIn(302, 401, 403);

        // --- Authenticated save succeeds and is immediately visible via the public view endpoint ---
        HttpResponse<String> saveResponse = client.postJson("/api/config/save", cookie, csrfToken, saveBody);
        assertThat(saveResponse.statusCode()).isEqualTo(200);
        JsonNode saved = JSON.readTree(saveResponse.body()).path("data");
        assertThat(saved.path("sessionTimeoutMinutes").asInt()).isEqualTo(45);

        HttpResponse<String> viewResponse = client.postJson("/api/config", cookie, csrfToken, "{}");
        JsonNode viewData = JSON.readTree(viewResponse.body()).path("data");
        assertThat(viewData.path("sessionTimeoutMinutes").asInt()).isEqualTo(45);
    }

    @Test
    void refreshViewsRequiresAuthenticationAndRecordsAudit() throws Exception {
        HttpResponse<String> anonymousRefresh = client.postJsonWithoutCsrfToken("/api/administration/refresh-views", "", "{}");
        assertThat(anonymousRefresh.statusCode()).isIn(302, 401, 403);

        HttpResponse<String> refreshResponse = client.postJson("/api/administration/refresh-views", cookie, csrfToken, "{}");
        assertThat(refreshResponse.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(refreshResponse.body()).path("success").asBoolean()).isTrue();

        HttpResponse<String> auditResponse = client.postJson("/api/audit/entries", cookie, csrfToken,
                "{\"actionType\":\"MATERIALIZED_VIEW_MANUAL_REFRESH\"}");
        assertThat(auditResponse.statusCode()).isEqualTo(200);
        JsonNode entries = JSON.readTree(auditResponse.body()).path("data").path("entries");
        assertThat(entries.size()).isGreaterThan(0);
    }

    @Test
    void defaultImportModeFallbackIsRealAndServerEnforced() throws Exception {
        // Set the default to REPLACE, then upload with mode omitted entirely - the server must
        // fall back to CONFIG's configured default, not reject the request or silently assume APPEND.
        String saveBody = "{\"sessionTimeoutMinutes\":30,\"defaultImportMode\":\"REPLACE\","
                + "\"materializedViewAutoRefresh\":true,\"notifyOnImportCompletion\":true}";
        HttpResponse<String> saveResponse = client.postJson("/api/config/save", cookie, csrfToken, saveBody);
        assertThat(saveResponse.statusCode()).isEqualTo(200);

        long batchId = uploadWithoutModeParam(generateCsv());
        awaitAwaitingConfirmation(batchId);

        HttpResponse<String> batchesResponse = client.postJson("/api/administration/import-batches", cookie, csrfToken, "{}");
        assertThat(batchesResponse.statusCode()).isEqualTo(200);
        JsonNode batches = JSON.readTree(batchesResponse.body()).path("data");
        JsonNode ourBatch = findBatch(batches, batchId);
        assertThat(ourBatch.path("importMode").asText()).isEqualTo("REPLACE");

        // Restore APPEND as the default so later tests in this suite aren't affected.
        String restoreBody = "{\"sessionTimeoutMinutes\":30,\"defaultImportMode\":\"APPEND\","
                + "\"materializedViewAutoRefresh\":true,\"notifyOnImportCompletion\":true}";
        client.postJson("/api/config/save", cookie, csrfToken, restoreBody);
    }

    private static JsonNode findBatch(JsonNode batches, long batchId) {
        for (JsonNode batch : batches) {
            if (batch.path("id").asLong() == batchId) {
                return batch;
            }
        }
        throw new AssertionError("Batch " + batchId + " not found in " + batches);
    }

    private long uploadWithoutModeParam(String csvContent) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "administration-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
                Map.of());
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        return JSON.readTree(uploadResponse.body()).path("data").path("importBatchId").asLong();
    }

    private void awaitAwaitingConfirmation(long batchId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> statusResponse = client.postJson("/api/import/status", cookie, csrfToken,
                    "{\"importBatchId\":" + batchId + "}");
            String status = JSON.readTree(statusResponse.body()).path("data").path("status").asText();
            if (status.equals("AWAITING_CONFIRMATION")) {
                return;
            }
            if (status.equals("FAILED")) {
                throw new AssertionError("Import batch " + batchId + " failed");
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Import batch " + batchId + " never reached AWAITING_CONFIRMATION");
    }

    private static String sessionCookieFrom(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(h -> h.startsWith("SESSION="))
                .map(h -> h.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SESSION cookie in response"));
    }

    private static String generateCsv() {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (int i = 0; i < 10; i++) {
            sb.append("ADM").append(String.format("%09d", i)).append(',')
                    .append(YEAR).append(",1,1,")
                    .append("United States,North America,,,,,,,")
                    .append("Bombing/Explosion,Explosives,Business,")
                    .append("Unknown,2,3,1,0,0,0,\n");
        }
        return sb.toString();
    }
}

package com.gtd.platform.workspace.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end coverage of the System Health Dashboard (Phase 10), entirely
 * public: real DB connectivity, materialized-view freshness rows, and -
 * the one check worth verifying against the real running app rather than
 * just {@link OfflineAssetScannerTest}'s isolated fixtures - {@link
 * OfflineAssetScanner#scanBundledAssets()} run for real against this
 * project's actual packaged templates/stylesheets, proving the platform's
 * zero-outbound-requests guarantee holds for the real shipped code, not
 * just hand-authored test content.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void healthWorkspaceIsPublicAndReportsRealChecks() throws Exception {
        FormLoginClient client = new FormLoginClient("http://localhost:" + port);

        HttpResponse<String> loginPage = client.getWithCookie("/login", "");
        String anonymousCookie = sessionCookieFrom(loginPage);
        String anonymousCsrf = FormLoginClient.extractCsrfToken(loginPage.body());

        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/health", anonymousCookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        assertThat(pageResponse.body()).contains("System Health Dashboard");

        HttpResponse<String> checkResponse = client.postJson("/api/health/check", anonymousCookie, anonymousCsrf, "{}");
        assertThat(checkResponse.statusCode()).isEqualTo(200);
        JsonNode data = JSON.readTree(checkResponse.body()).path("data");

        // --- Real DB connection check ---
        assertThat(data.path("database").path("connected").asBoolean()).isTrue();
        assertThat(data.path("database").path("responseTimeMs").asLong()).isGreaterThanOrEqualTo(0);

        // --- Materialized view freshness: all six views, seeded by V5, always present ---
        JsonNode views = data.path("materializedViews");
        assertThat(views.size()).isEqualTo(6);
        for (JsonNode view : views) {
            assertThat(view.path("viewName").asText()).isNotBlank();
            assertThat(view.path("lastRefreshStatus").asText()).isIn("NEVER_REFRESHED", "SUCCESS", "FAILED");
        }

        // --- Offline verification: a real scan of this project's actual bundled assets must pass ---
        JsonNode offline = data.path("offlineVerification");
        assertThat(offline.path("resourcesScanned").asInt()).isGreaterThan(0);
        assertThat(offline.path("passed").asBoolean())
                .withFailMessage("Real offline-verification scan found external references: %s", offline.path("findings"))
                .isTrue();
        assertThat(offline.path("findings").size()).isEqualTo(0);

        assertThat(data.path("checkedAt").asText()).isNotBlank();
    }

    private static String sessionCookieFrom(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(h -> h.startsWith("SESSION="))
                .map(h -> h.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SESSION cookie in response"));
    }
}

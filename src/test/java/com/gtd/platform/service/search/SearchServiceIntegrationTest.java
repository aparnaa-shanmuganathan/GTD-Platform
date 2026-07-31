package com.gtd.platform.service.search;

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
 * End-to-end coverage of SEARCH (Phase 10): {@code POST /api/search} is
 * public and returns real data - real seeded/imported countries and
 * organizations (queried live, AWS-UNK-001 excluding each dimension's
 * seeded {@code is_unknown} row), the fixed workspace/action list, and an
 * empty query returning the default (workspace + action) results. Uses
 * "United States" - one of the 26 countries Phase 1 seeded directly via
 * migration (CLAUDE.md section 12, Phase 1 decisions), not dependent on
 * any other test class's own import having run first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchServiceIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void searchIsPublicAndReturnsRealCountryAndWorkspaceMatches() throws Exception {
        FormLoginClient client = new FormLoginClient("http://localhost:" + port);
        HttpResponse<String> loginPage = client.getWithCookie("/login", "");
        String anonymousCookie = sessionCookieFrom(loginPage);
        String anonymousCsrf = FormLoginClient.extractCsrfToken(loginPage.body());

        // --- A real, seeded country ---
        HttpResponse<String> countryResponse = client.postJson("/api/search", anonymousCookie, anonymousCsrf,
                "{\"query\":\"United States\"}");
        assertThat(countryResponse.statusCode()).isEqualTo(200);
        JsonNode countryResults = JSON.readTree(countryResponse.body()).path("data");
        assertThat(hasResultWithLabelAndGroup(countryResults, "United States", "Country")).isTrue();

        // --- A workspace match ---
        HttpResponse<String> workspaceResponse = client.postJson("/api/search", anonymousCookie, anonymousCsrf,
                "{\"query\":\"Executive\"}");
        JsonNode workspaceResults = JSON.readTree(workspaceResponse.body()).path("data");
        assertThat(hasResultWithLabelAndGroup(workspaceResults, "Executive", "Workspace")).isTrue();
        for (JsonNode result : workspaceResults) {
            if ("Workspace".equals(result.path("group").asText())) {
                assertThat(result.path("url").asText()).isEqualTo("/workspace/executive");
            }
        }

        // --- Empty query returns the default (workspace + action) list, capped, no DB dimension noise ---
        HttpResponse<String> emptyResponse = client.postJson("/api/search", anonymousCookie, anonymousCsrf, "{\"query\":\"\"}");
        JsonNode emptyResults = JSON.readTree(emptyResponse.body()).path("data");
        assertThat(emptyResults.size()).isGreaterThan(0);
        for (JsonNode result : emptyResults) {
            assertThat(result.path("group").asText()).isIn("Workspace", "Action");
        }

        // --- No match returns an empty list, not an error ---
        HttpResponse<String> noMatchResponse = client.postJson("/api/search", anonymousCookie, anonymousCsrf,
                "{\"query\":\"zzz-no-such-thing-zzz\"}");
        assertThat(noMatchResponse.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(noMatchResponse.body()).path("data")).isEmpty();
    }

    private static boolean hasResultWithLabelAndGroup(JsonNode results, String label, String group) {
        for (JsonNode result : results) {
            if (label.equals(result.path("label").asText()) && group.equals(result.path("group").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String sessionCookieFrom(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(h -> h.startsWith("SESSION="))
                .map(h -> h.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SESSION cookie in response"));
    }
}

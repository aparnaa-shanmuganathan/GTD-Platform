package com.gtd.platform.workspace.target;

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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * End-to-end coverage of the Target Workspace (CLAUDE.md section 12, Phase
 * 8): imports a real (if synthetic) dataset - including some rows with a
 * blank {@code targtype1_txt}, which Phase 3's import resolves to the
 * seeded {@code is_unknown} row (AWS-UNK-001) - through Phase 3's pipeline,
 * then verifies the dashboard's KPIs, target-type ranking (excluding that
 * unknown row), and incidents-by-year trend all work against real,
 * confirmed data - never mock data. The simplest of the six Phase 8
 * workspaces, so the simplest test: nothing here is genuinely new
 * computation, only reused shared components plus one ranking method.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TargetWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ROW_COUNT = 4_000;
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
        String username = "target-it-user-" + UUID.randomUUID();
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
    void targetWorkspaceRendersRealDataWithRankingAndTrend() throws Exception {
        importAndConfirm(generateCsv(ROW_COUNT));

        // --- GET /workspace/target renders real, unfiltered dashboard data ---
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/target", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        String body = pageResponse.body();
        assertThat(body).contains("Target Workspace");
        assertThat(body).contains("targetTypeRanking");
        assertThat(body).contains("Business");

        // --- POST /api/target/dashboard: unfiltered ---
        HttpResponse<String> dashboardResponse = client.postJson("/api/target/dashboard", cookie, csrfToken, "{}");
        assertThat(dashboardResponse.statusCode()).isEqualTo(200);
        JsonNode data = JSON.readTree(dashboardResponse.body()).path("data");

        assertThat(data.path("kpis").size()).isEqualTo(6);

        // --- Ranking excludes the seeded is_unknown target type row (AWS-UNK-001) ---
        JsonNode rankingEntries = data.path("ranking").path("entries");
        Set<String> rankedTargetTypeNames = new HashSet<>();
        rankingEntries.forEach(entry -> rankedTargetTypeNames.add(entry.path("label").asText()));
        assertThat(rankedTargetTypeNames).contains("Business", "Government (General)", "Police", "Military");
        assertThat(rankedTargetTypeNames).doesNotContain("Unknown");

        // --- Trend chart ---
        JsonNode trendChart = data.path("trendChart");
        assertThat(trendChart.path("id").asText()).isEqualTo("trendChart");
        assertThat(trendChart.path("type").asText()).isEqualTo("LINE");
        assertThat(trendChart.path("categories").size()).isGreaterThan(0);

        // --- Filter narrows the ranking total ---
        long unfilteredTotal = sumRankingValues(rankingEntries);
        HttpResponse<String> filteredResponse = client.postJson("/api/target/dashboard", cookie, csrfToken,
                "{\"startDate\":\"2130-01-01\",\"endDate\":\"2130-12-31\"}");
        assertThat(filteredResponse.statusCode()).isEqualTo(200);
        long filteredTotal = sumRankingValues(
                JSON.readTree(filteredResponse.body()).path("data").path("ranking").path("entries"));
        assertThat(filteredTotal).isGreaterThan(0).isLessThan(unfilteredTotal);
    }

    private static long sumRankingValues(JsonNode entries) {
        long total = 0;
        for (JsonNode entry : entries) {
            total += entry.path("value").asLong();
        }
        return total;
    }

    private void importAndConfirm(String csvContent) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "target-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
                Map.of("mode", "APPEND"));
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        long batchId = JSON.readTree(uploadResponse.body()).path("data").path("importBatchId").asLong();

        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> statusResponse = client.postJson("/api/import/status", cookie, csrfToken,
                    "{\"importBatchId\":" + batchId + "}");
            String status = JSON.readTree(statusResponse.body()).path("data").path("status").asText();
            if (status.equals("AWAITING_CONFIRMATION")) {
                break;
            }
            if (status.equals("FAILED")) {
                throw new AssertionError("Import batch " + batchId + " failed");
            }
            Thread.sleep(150);
        }

        HttpResponse<String> confirmResponse = client.postJson("/api/import/confirm", cookie, csrfToken,
                "{\"importBatchId\":" + batchId + "}");
        assertThat(confirmResponse.statusCode()).isEqualTo(200);
    }

    private static String generateCsv(int rowCount) {
        String[] countries = { "United States", "Canada", "Iraq", "Afghanistan", "Nigeria" };
        String[] regions = { "North America", "North America", "Middle East & North Africa", "South Asia", "Sub-Saharan Africa" };
        String[] targetTypes = { "Business", "Government (General)", "Police", "Military" };
        String[] orgs = { "Taliban", "Al-Shabaab", "Unknown", "Boko Haram" };

        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (int i = 0; i < rowCount; i++) {
            int countryIdx = i % countries.length;
            int orgIdx = i % orgs.length;
            // A distinctive, non-overlapping year range - see CLAUDE.md section 12's Phase 8
            // decisions for the full reserved-range list (Target: 2130s).
            int year = 2130 + (i % 8);
            int nkill = i % 10;
            int nwound = (i * 3) % 15;
            String success = (i % 4 != 0) ? "1" : "0";
            // Every 10th row leaves targtype1_txt blank - Phase 3's import resolves a blank
            // target-type value to the seeded is_unknown dim_target_type row (AWS-UNK-001).
            String targetType = (i % 10 == 0) ? "" : targetTypes[i % targetTypes.length];

            sb.append("TGT").append(String.format("%09d", i)).append(',')
                    .append(year).append(",1,1,")
                    .append(countries[countryIdx]).append(',').append(regions[countryIdx]).append(",,,,,,,")
                    .append("Bombing/Explosion,Explosives,").append(targetType).append(',')
                    .append(orgs[orgIdx]).append(',').append(nkill).append(',').append(nwound).append(',')
                    .append(success).append(",0,0,0,\n");
        }
        return sb.toString();
    }
}

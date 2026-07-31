package com.gtd.platform.workspace.weapon;

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
 * End-to-end coverage of the Weapon Workspace (CLAUDE.md section 12, Phase
 * 8): imports a small, hand-computable real dataset through Phase 3's
 * pipeline, then verifies the dashboard's ranking (excluding the seeded
 * is_unknown weapon type row) and the casualty-severity chart's per-weapon
 * average fatalities/injuries against exact hand-computed values - not
 * just "a number comes back" - the same rigor {@code KpiAggregationServiceTest}/
 * {@code TrendServiceTest} hold SVC-AGG/SVC-TREND to.
 *
 * <p>Scoped to a single reserved year (2120) via an explicit date-range
 * filter on every assertion query, rather than relying on this test's own
 * synthetic rows being the only "Explosives"/"Firearms" rows in the shared,
 * never-rolled-back test database - several other integration test classes
 * also use "Explosives" as a hardcoded weapon type.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeaponWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSV_HEADER = "eventid,iyear,imonth,iday,country_txt,region_txt,provstate,city,"
            + "latitude,longitude,summary,target1,attacktype1_txt,weaptype1_txt,targtype1_txt,gname,nkill,nwound,"
            + "success,suicide,individual,property,motive";
    private static final int YEAR = 2120;

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
        String username = "weapon-it-user-" + UUID.randomUUID();
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

    /**
     * Explosives: nkill 2 &amp; 4 -&gt; avg 3.00; nwound 4 &amp; 0 -&gt; avg 2.00.
     * Firearms: nkill 1 &amp; NULL -&gt; avg 1.00 (NULL skipped, not coalesced to zero);
     * nwound 1 &amp; 3 -&gt; avg 2.00.
     * A fifth row leaves weaptype1_txt blank -&gt; resolves to the seeded is_unknown
     * dim_weapon_type row (AWS-UNK-001) - must be excluded from both the ranking and the chart.
     */
    @Test
    void weaponWorkspaceRendersRealDataWithRankingAndSeverityChart() throws Exception {
        String csv = CSV_HEADER + "\n"
                + row("WPN000000001", "Explosives", "2", "4")
                + row("WPN000000002", "Explosives", "4", "0")
                + row("WPN000000003", "Firearms", "1", "1")
                + row("WPN000000004", "Firearms", "", "3")
                + row("WPN000000005", "", "5", "5");
        importAndConfirm(csv);

        // --- GET /workspace/weapon renders real, unfiltered dashboard data ---
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/weapon", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        String body = pageResponse.body();
        assertThat(body).contains("Weapon Workspace");
        assertThat(body).contains("weaponTypeRanking");

        // --- POST /api/weapon/dashboard, scoped to this test's own year ---
        HttpResponse<String> dashboardResponse = client.postJson("/api/weapon/dashboard", cookie, csrfToken,
                "{\"startDate\":\"" + YEAR + "-01-01\",\"endDate\":\"" + YEAR + "-12-31\"}");
        assertThat(dashboardResponse.statusCode()).isEqualTo(200);
        JsonNode data = JSON.readTree(dashboardResponse.body()).path("data");

        assertThat(data.path("kpis").size()).isEqualTo(6);
        assertThat(data.path("kpis").get(0).path("value").asInt()).isEqualTo(5);

        // --- Ranking: Explosives=2, Firearms=2, Unknown excluded (AWS-UNK-001) ---
        JsonNode rankingEntries = data.path("ranking").path("entries");
        Map<String, Integer> rankedCounts = new java.util.HashMap<>();
        rankingEntries.forEach(entry -> rankedCounts.put(entry.path("label").asText(), entry.path("value").asInt()));
        assertThat(rankedCounts).containsEntry("Explosives", 2).containsEntry("Firearms", 2);
        assertThat(rankedCounts).doesNotContainKey("Unknown");

        // --- Severity chart: exact hand-computed averages, Unknown excluded ---
        JsonNode severityChart = data.path("severityChart");
        assertThat(severityChart.path("id").asText()).isEqualTo("weaponSeverityChart");
        assertThat(severityChart.path("type").asText()).isEqualTo("BAR");
        java.util.List<String> categories = new java.util.ArrayList<>();
        severityChart.path("categories").forEach(c -> categories.add(c.asText()));
        assertThat(categories).containsExactly("Explosives", "Firearms");
        assertThat(categories).doesNotContain("Unknown");

        JsonNode series = severityChart.path("series");
        assertThat(series.size()).isEqualTo(2);
        assertThat(series.get(0).path("name").asText()).isEqualTo("Avg fatalities/incident");
        assertThat(series.get(0).path("data").get(0).decimalValue()).isEqualByComparingTo("3.00");
        assertThat(series.get(0).path("data").get(1).decimalValue()).isEqualByComparingTo("1.00");
        assertThat(series.get(1).path("name").asText()).isEqualTo("Avg injuries/incident");
        assertThat(series.get(1).path("data").get(0).decimalValue()).isEqualByComparingTo("2.00");
        assertThat(series.get(1).path("data").get(1).decimalValue()).isEqualByComparingTo("2.00");
    }

    private static String row(String eventId, String weaponType, String nkill, String nwound) {
        return eventId + "," + YEAR + ",1,1,United States,North America,,,,,,,"
                + "Bombing/Explosion," + weaponType + ",Business,Taliban,"
                + nkill + "," + nwound + ",1,0,0,0,\n";
    }

    private void importAndConfirm(String csvContent) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "weapon-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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
}

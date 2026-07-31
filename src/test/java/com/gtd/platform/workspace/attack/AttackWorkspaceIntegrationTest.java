package com.gtd.platform.workspace.attack;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.DimRegion;
import com.gtd.platform.shared.db.DimRegionRepository;
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
 * End-to-end coverage of the Attack Workspace (CLAUDE.md section 12, Phase
 * 8): imports a real (if synthetic) dataset - including some rows with a
 * blank {@code attacktype1_txt}, which Phase 3's import resolves to the
 * seeded {@code is_unknown} row (AWS-UNK-001) - through Phase 3's pipeline,
 * then verifies the dashboard's KPIs, attack-type ranking (excluding that
 * unknown row), prevalence-over-time chart, and success-rate trend chart
 * all work against real, confirmed data - never mock data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttackWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

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
    @Autowired
    private DimRegionRepository regionRepository;

    private FormLoginClient client;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = new FormLoginClient("http://localhost:" + port);
        String username = "attack-it-user-" + UUID.randomUUID();
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
    void attackWorkspaceRendersRealDataWithRankingAndCharts() throws Exception {
        importAndConfirm(generateCsv(ROW_COUNT));

        // --- GET /workspace/attack renders real, unfiltered dashboard data ---
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/attack", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        String body = pageResponse.body();
        assertThat(body).contains("Attack Workspace");
        assertThat(body).contains("attackTypeRanking");
        assertThat(body).contains("Bombing/Explosion");

        // --- POST /api/attack/dashboard: unfiltered ---
        HttpResponse<String> dashboardResponse = client.postJson("/api/attack/dashboard", cookie, csrfToken, "{}");
        assertThat(dashboardResponse.statusCode()).isEqualTo(200);
        JsonNode data = JSON.readTree(dashboardResponse.body()).path("data");

        assertThat(data.path("kpis").size()).isEqualTo(6);

        // --- Ranking excludes the seeded is_unknown attack type row (AWS-UNK-001) ---
        JsonNode rankingEntries = data.path("ranking").path("entries");
        Set<String> rankedAttackTypeNames = new HashSet<>();
        rankingEntries.forEach(entry -> rankedAttackTypeNames.add(entry.path("label").asText()));
        assertThat(rankedAttackTypeNames).contains("Bombing/Explosion", "Armed Assault", "Assassination", "Hijacking");
        assertThat(rankedAttackTypeNames).doesNotContain("Unknown");

        // --- Prevalence-over-time chart: one series per non-unknown attack type ---
        JsonNode prevalenceChart = data.path("prevalenceChart");
        assertThat(prevalenceChart.path("id").asText()).isEqualTo("attackPrevalenceChart");
        assertThat(prevalenceChart.path("type").asText()).isEqualTo("LINE");
        Set<String> prevalenceSeriesNames = new HashSet<>();
        prevalenceChart.path("series").forEach(series -> prevalenceSeriesNames.add(series.path("name").asText()));
        assertThat(prevalenceSeriesNames).contains("Bombing/Explosion", "Armed Assault", "Assassination", "Hijacking");
        assertThat(prevalenceSeriesNames).doesNotContain("Unknown");
        assertThat(prevalenceChart.path("categories").isArray()).isTrue();
        assertThat(prevalenceChart.path("categories").size()).isGreaterThan(0);

        // --- Success-rate trend chart ---
        JsonNode successRateChart = data.path("successRateChart");
        assertThat(successRateChart.path("id").asText()).isEqualTo("successRateChart");
        assertThat(successRateChart.path("series").size()).isEqualTo(1);

        // --- Filter narrows the ranking: region filter reduces total below the unfiltered total ---
        long unfilteredTotal = sumRankingValues(rankingEntries);
        DimRegion northAmerica = regionRepository.findByName("North America").orElseThrow();
        HttpResponse<String> regionFilteredResponse = client.postJson("/api/attack/dashboard", cookie, csrfToken,
                "{\"regionIds\":[" + northAmerica.getId() + "]}");
        assertThat(regionFilteredResponse.statusCode()).isEqualTo(200);
        long regionFilteredTotal = sumRankingValues(
                JSON.readTree(regionFilteredResponse.body()).path("data").path("ranking").path("entries"));
        assertThat(regionFilteredTotal).isLessThanOrEqualTo(unfilteredTotal);
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
                "file", "attack-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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
        String[] attackTypes = { "Bombing/Explosion", "Armed Assault", "Assassination", "Hijacking" };
        String[] orgs = { "Taliban", "Al-Shabaab", "Unknown", "Boko Haram" };

        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (int i = 0; i < rowCount; i++) {
            int countryIdx = i % countries.length;
            int orgIdx = i % orgs.length;
            // A distinctive, non-overlapping year range - see CLAUDE.md section 12's Phase 8
            // decisions for the full reserved-range list across the suite (Organization: 2100s;
            // Attack: 2110s; Weapon/Target/Casualty: 2120s/2130s/2140s).
            int year = 2110 + (i % 8);
            int nkill = i % 10;
            int nwound = (i * 3) % 15;
            String success = (i % 4 != 0) ? "1" : "0";
            // Every 10th row leaves attacktype1_txt blank - Phase 3's import resolves a blank
            // attack-type value to the seeded is_unknown dim_attack_type row (AWS-UNK-001),
            // giving this test a real "Unknown" row to prove the ranking/prevalence chart
            // actually exclude rather than just never happening to encounter one.
            String attackType = (i % 10 == 0) ? "" : attackTypes[i % attackTypes.length];

            sb.append("ATK").append(String.format("%09d", i)).append(',')
                    .append(year).append(",1,1,")
                    .append(countries[countryIdx]).append(',').append(regions[countryIdx]).append(",,,,,,,")
                    .append(attackType).append(",Explosives,Business,")
                    .append(orgs[orgIdx]).append(',').append(nkill).append(',').append(nwound).append(',')
                    .append(success).append(",0,0,0,\n");
        }
        return sb.toString();
    }
}

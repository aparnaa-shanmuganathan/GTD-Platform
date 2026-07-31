package com.gtd.platform.workspace.region;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.DimAttackType;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
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
 * End-to-end coverage of the Region Workspace (CLAUDE.md section 12, Phase
 * 8): imports a real (if synthetic) dataset through Phase 3's pipeline, then
 * verifies the region ranking, the region drill-down (KPIs/trend/member-
 * country breakdown - all reused SVC-AGG/SVC-TREND/{@code
 * RankingAndBreakdownService} scoped to one region), and the
 * ERR-REGION-00x validation paths all work against that real, confirmed
 * data - never mock data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegionWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

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
    @Autowired
    private DimAttackTypeRepository attackTypeRepository;

    private FormLoginClient client;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = new FormLoginClient("http://localhost:" + port);
        String username = "region-it-user-" + UUID.randomUUID();
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
    void regionWorkspaceRendersRealDataAndSupportsRankingAndDrilldown() throws Exception {
        importAndConfirm(generateCsv(ROW_COUNT));

        DimRegion northAmerica = regionRepository.findByName("North America").orElseThrow();

        // --- GET /workspace/region renders real, unfiltered ranking data ---
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/region", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        String body = pageResponse.body();
        assertThat(body).contains("Region Workspace");
        assertThat(body).contains("regionRanking");
        assertThat(body).contains("North America");
        assertThat(body).contains("\"entityId\":" + northAmerica.getId());

        // --- POST /api/region/ranking: unfiltered ---
        HttpResponse<String> rankingResponse = client.postJson("/api/region/ranking", cookie, csrfToken, "{}");
        assertThat(rankingResponse.statusCode()).isEqualTo(200);
        JsonNode rankingEntries = JSON.readTree(rankingResponse.body()).path("data").path("entries");
        assertThat(rankingEntries.isArray()).isTrue();
        Set<String> rankedRegionNames = new HashSet<>();
        rankingEntries.forEach(entry -> rankedRegionNames.add(entry.path("label").asText()));
        assertThat(rankedRegionNames).contains("North America", "Middle East & North Africa",
                "South Asia", "Sub-Saharan Africa");

        // --- POST /api/region/ranking respects the attack-type filter: total narrows below
        // the true unfiltered total (queried fresh, not assumed as ROW_COUNT - shared,
        // never-rolled-back database across every integration test class). ---
        long unfilteredTotal = sumRankingValues(rankingEntries);
        DimAttackType bombing = attackTypeRepository.findByName("Bombing/Explosion").orElseThrow();
        HttpResponse<String> attackFilteredResponse = client.postJson("/api/region/ranking", cookie, csrfToken,
                "{\"attackTypeIds\":[" + bombing.getId() + "]}");
        assertThat(attackFilteredResponse.statusCode()).isEqualTo(200);
        long attackFilteredTotal = sumRankingValues(JSON.readTree(attackFilteredResponse.body()).path("data").path("entries"));
        assertThat(attackFilteredTotal).isGreaterThan(0).isLessThan(unfilteredTotal);

        // --- POST /api/region/drilldown: North America -> United States + Canada, never
        // this test's own non-North-America countries (Iraq/Afghanistan/Nigeria). Not an
        // exact-set assertion: the shared test database may carry other North America
        // countries created by unrelated test classes (e.g. ImportPipelineIntegrationTest's
        // "Freedonia"). ---
        HttpResponse<String> drilldownResponse = client.postJson("/api/region/drilldown", cookie, csrfToken,
                "{\"regionIds\":[" + northAmerica.getId() + "]}");
        assertThat(drilldownResponse.statusCode()).isEqualTo(200);
        JsonNode drilldownData = JSON.readTree(drilldownResponse.body()).path("data");
        assertThat(drilldownData.path("regionName").asText()).isEqualTo("North America");
        assertThat(drilldownData.path("kpis").size()).isEqualTo(6);
        assertThat(drilldownData.path("trendChart").path("id").asText()).isEqualTo("trendChart");
        int drilldownIncidents = drilldownData.path("kpis").get(0).path("value").asInt();
        assertThat(drilldownIncidents).isGreaterThan(0).isLessThan(ROW_COUNT * 2);

        Set<String> memberCountryNames = new HashSet<>();
        drilldownData.path("memberCountryRanking").path("entries")
                .forEach(entry -> memberCountryNames.add(entry.path("label").asText()));
        assertThat(memberCountryNames).contains("United States", "Canada");
        assertThat(memberCountryNames).doesNotContain("Iraq", "Afghanistan", "Nigeria");

        // --- Validation: drilldown with no regionIds ---
        HttpResponse<String> noneResponse = client.postJson("/api/region/drilldown", cookie, csrfToken, "{}");
        assertThat(noneResponse.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(noneResponse.body()).path("error").path("code").asText()).isEqualTo("ERR-REGION-001");

        // --- Validation: drilldown with 2 regionIds ---
        DimRegion southAsia = regionRepository.findByName("South Asia").orElseThrow();
        HttpResponse<String> tooManyResponse = client.postJson("/api/region/drilldown", cookie, csrfToken,
                "{\"regionIds\":[" + northAmerica.getId() + "," + southAsia.getId() + "]}");
        assertThat(tooManyResponse.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(tooManyResponse.body()).path("error").path("code").asText()).isEqualTo("ERR-REGION-001");

        // --- Validation: nonexistent region id ---
        HttpResponse<String> notFoundResponse = client.postJson("/api/region/drilldown", cookie, csrfToken,
                "{\"regionIds\":[999999999]}");
        assertThat(notFoundResponse.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(notFoundResponse.body()).path("error").path("code").asText()).isEqualTo("ERR-REGION-002");
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
                "file", "region-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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
            int attackIdx = i % attackTypes.length;
            int orgIdx = i % orgs.length;
            // A distinctive, non-overlapping year range - Executive uses 2015-2022, Country
            // uses 2040s; this test's own database-wide checks are already scoped/relative
            // rather than exact-count, but disjoint ranges avoid unnecessary cross-test
            // contamination between integration test classes sharing this database.
            int year = 2060 + (i % 8);
            int nkill = i % 10;
            int nwound = (i * 3) % 15;
            String success = (i % 4 != 0) ? "1" : "0";

            sb.append("RGN").append(String.format("%09d", i)).append(',')
                    .append(year).append(",1,1,")
                    .append(countries[countryIdx]).append(',').append(regions[countryIdx]).append(",,,,,,,")
                    .append(attackTypes[attackIdx]).append(",Explosives,Business,")
                    .append(orgs[orgIdx]).append(',').append(nkill).append(',').append(nwound).append(',')
                    .append(success).append(",0,0,0,\n");
        }
        return sb.toString();
    }
}

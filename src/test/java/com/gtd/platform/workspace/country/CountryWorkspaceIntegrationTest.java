package com.gtd.platform.workspace.country;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.DimAttackType;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimCountry;
import com.gtd.platform.shared.db.DimCountryRepository;
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
 * End-to-end coverage of the Country Workspace (CLAUDE.md section 12, Phase
 * 5): imports a real (if synthetic) dataset through Phase 3's pipeline, then
 * verifies the ranking view, the compare endpoint (2-4 countries), the
 * drill-down endpoint (1 country), and the ERR-COMPARE-00x validation paths
 * all work against that real, confirmed data - never mock data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CountryWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

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
    private DimCountryRepository countryRepository;
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
        String username = "country-it-user-" + UUID.randomUUID();
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
    void countryWorkspaceRendersRealDataAndSupportsRankingCompareAndDrilldown() throws Exception {
        importAndConfirm(generateCsv(ROW_COUNT));

        DimCountry us = countryRepository.findByName("United States").orElseThrow();
        DimCountry canada = countryRepository.findByName("Canada").orElseThrow();
        DimCountry iraq = countryRepository.findByName("Iraq").orElseThrow();
        DimCountry afghanistan = countryRepository.findByName("Afghanistan").orElseThrow();

        // --- GET /workspace/country renders real, unfiltered ranking data ---
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/country", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        String body = pageResponse.body();
        assertThat(body).contains("Country Workspace");
        assertThat(body).contains("countryRanking");
        assertThat(body).contains("United States");
        // Every ranking entry carries the entity id a click needs to drill down with.
        assertThat(body).contains("\"entityId\":" + us.getId());

        // --- POST /api/country/ranking respects the date-range filter ---
        String rankingFilter = "{\"startDate\":\"2015-01-01\",\"endDate\":\"2015-12-31\"}";
        HttpResponse<String> rankingResponse = client.postJson("/api/country/ranking", cookie, csrfToken, rankingFilter);
        assertThat(rankingResponse.statusCode()).isEqualTo(200);
        JsonNode rankingJson = JSON.readTree(rankingResponse.body());
        assertThat(rankingJson.path("success").asBoolean()).isTrue();
        assertThat(rankingJson.path("data").path("entries").isArray()).isTrue();

        // --- POST /api/country/ranking respects the region filter: this test's own North
        // America countries (United States, Canada) must appear, and its own countries from
        // other regions (Iraq, Afghanistan, Nigeria) must not. Not asserted as an exact set -
        // this integration test shares one never-rolled-back database with every other
        // integration test class (PostgresIntegrationTestBase), and at least one of them
        // (ImportPipelineIntegrationTest) legitimately creates its own extra North America
        // country ("Freedonia") there too. ---
        DimRegion northAmerica = regionRepository.findByName("North America").orElseThrow();
        String regionFilter = "{\"regionIds\":[" + northAmerica.getId() + "]}";
        HttpResponse<String> regionFilteredResponse = client.postJson("/api/country/ranking", cookie, csrfToken, regionFilter);
        assertThat(regionFilteredResponse.statusCode()).isEqualTo(200);
        JsonNode regionFilteredEntries = JSON.readTree(regionFilteredResponse.body()).path("data").path("entries");
        Set<String> regionFilteredLabels = new HashSet<>();
        for (JsonNode entry : regionFilteredEntries) {
            regionFilteredLabels.add(entry.path("label").asText());
        }
        assertThat(regionFilteredLabels).contains("United States", "Canada");
        assertThat(regionFilteredLabels).doesNotContain("Iraq", "Afghanistan", "Nigeria");

        // --- POST /api/country/ranking respects the attack-type filter: narrowing to one
        // attack type must reduce the summed incident count below the true unfiltered total
        // (queried fresh here rather than assumed as ROW_COUNT, since this database is shared,
        // never-rolled-back across every integration test class). ---
        HttpResponse<String> unfilteredRankingResponse = client.postJson("/api/country/ranking", cookie, csrfToken, "{}");
        long unfilteredTotal = sumRankingValues(JSON.readTree(unfilteredRankingResponse.body()).path("data").path("entries"));

        DimAttackType bombing = attackTypeRepository.findByName("Bombing/Explosion").orElseThrow();
        String attackFilter = "{\"attackTypeIds\":[" + bombing.getId() + "]}";
        HttpResponse<String> attackFilteredResponse = client.postJson("/api/country/ranking", cookie, csrfToken, attackFilter);
        assertThat(attackFilteredResponse.statusCode()).isEqualTo(200);
        long attackFilteredTotal = sumRankingValues(JSON.readTree(attackFilteredResponse.body()).path("data").path("entries"));
        assertThat(attackFilteredTotal).isGreaterThan(0).isLessThan(unfilteredTotal);

        // --- POST /api/country/compare: 4 real countries ---
        String compareBody = "{\"countryIds\":[" + us.getId() + "," + canada.getId() + ","
                + iraq.getId() + "," + afghanistan.getId() + "]}";
        HttpResponse<String> compareResponse = client.postJson("/api/country/compare", cookie, csrfToken, compareBody);
        assertThat(compareResponse.statusCode()).isEqualTo(200);
        JsonNode compareJson = JSON.readTree(compareResponse.body());
        assertThat(compareJson.path("success").asBoolean()).isTrue();
        JsonNode compareData = compareJson.path("data");
        assertThat(compareData.isArray()).isTrue();
        assertThat(compareData.size()).isEqualTo(4);
        assertThat(compareData.get(0).path("countryName").asText()).isEqualTo("United States");
        assertThat(compareData.get(0).path("kpis").size()).isEqualTo(6);
        assertThat(compareData.get(0).path("trendChart").path("id").asText()).isEqualTo("trendChart");

        // --- POST /api/country/drilldown: 1 real country ---
        String drilldownBody = "{\"countryIds\":[" + us.getId() + "]}";
        HttpResponse<String> drilldownResponse = client.postJson("/api/country/drilldown", cookie, csrfToken, drilldownBody);
        assertThat(drilldownResponse.statusCode()).isEqualTo(200);
        JsonNode drilldownJson = JSON.readTree(drilldownResponse.body());
        assertThat(drilldownJson.path("success").asBoolean()).isTrue();
        assertThat(drilldownJson.path("data").path("countryName").asText()).isEqualTo("United States");
        int drilldownIncidents = drilldownJson.path("data").path("kpis").get(0).path("value").asInt();
        assertThat(drilldownIncidents).isGreaterThan(0).isLessThan(ROW_COUNT);

        // --- Validation: compare with only 1 country ---
        HttpResponse<String> tooFewResponse = client.postJson("/api/country/compare", cookie, csrfToken,
                "{\"countryIds\":[" + us.getId() + "]}");
        assertThat(tooFewResponse.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(tooFewResponse.body()).path("error").path("code").asText()).isEqualTo("ERR-COMPARE-001");

        // --- Validation: drilldown with 2 countries ---
        HttpResponse<String> tooManyResponse = client.postJson("/api/country/drilldown", cookie, csrfToken,
                "{\"countryIds\":[" + us.getId() + "," + canada.getId() + "]}");
        assertThat(tooManyResponse.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(tooManyResponse.body()).path("error").path("code").asText()).isEqualTo("ERR-COMPARE-002");

        // --- Validation: nonexistent country id ---
        HttpResponse<String> notFoundResponse = client.postJson("/api/country/drilldown", cookie, csrfToken,
                "{\"countryIds\":[999999999]}");
        assertThat(notFoundResponse.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(notFoundResponse.body()).path("error").path("code").asText()).isEqualTo("ERR-COMPARE-003");
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
                "file", "country-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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
            // A distinctive, non-overlapping year range (2015-2022 is the Executive Workspace
            // integration test's own range) - this test's own database-wide checks are already
            // scoped/relative rather than exact-count, but keeping the ranges disjoint avoids
            // unnecessary cross-contamination between the two workspace integration tests that
            // share this same never-rolled-back test database (PostgresIntegrationTestBase).
            int year = 2040 + (i % 8);
            int nkill = i % 10;
            int nwound = (i * 3) % 15;
            String success = (i % 4 != 0) ? "1" : "0";

            sb.append("CTRY").append(String.format("%08d", i)).append(',')
                    .append(year).append(",1,1,")
                    .append(countries[countryIdx]).append(',').append(regions[countryIdx]).append(",,,,,,,")
                    .append(attackTypes[attackIdx]).append(",Explosives,Business,")
                    .append(orgs[orgIdx]).append(',').append(nkill).append(',').append(nwound).append(',')
                    .append(success).append(",0,0,0,\n");
        }
        return sb.toString();
    }
}

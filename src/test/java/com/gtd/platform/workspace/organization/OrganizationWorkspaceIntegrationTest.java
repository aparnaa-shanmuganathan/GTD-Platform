package com.gtd.platform.workspace.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.DimOrganization;
import com.gtd.platform.shared.db.DimOrganizationRepository;
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
 * End-to-end coverage of the Organization Workspace (CLAUDE.md section 12,
 * Phase 8): imports a real (if synthetic) dataset through Phase 3's
 * pipeline, then verifies the organization ranking excludes the seeded
 * {@code is_unknown} row (AWS-UNK-001), the KPI row's "Unattributed
 * incidents" figure is correct, the organization drill-down (KPIs/trend,
 * including "geographic reach" via SAV-005) works, and the
 * ERR-ORGANIZATION-00x validation paths all work against that real,
 * confirmed data - never mock data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

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
    private DimOrganizationRepository organizationRepository;

    private FormLoginClient client;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = new FormLoginClient("http://localhost:" + port);
        String username = "org-it-user-" + UUID.randomUUID();
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
    void organizationWorkspaceRendersRealDataAndSupportsRankingKpisAndDrilldown() throws Exception {
        importAndConfirm(generateCsv(ROW_COUNT));

        DimOrganization taliban = organizationRepository.findByName("Taliban").orElseThrow();

        // --- GET /workspace/organization renders real, unfiltered ranking + KPI data ---
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/organization", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        String body = pageResponse.body();
        assertThat(body).contains("Organization Workspace");
        assertThat(body).contains("organizationRanking");
        assertThat(body).contains("Taliban");

        // --- POST /api/organization/ranking excludes the seeded is_unknown row (AWS-UNK-001) ---
        HttpResponse<String> rankingResponse = client.postJson("/api/organization/ranking", cookie, csrfToken, "{}");
        assertThat(rankingResponse.statusCode()).isEqualTo(200);
        JsonNode rankingEntries = JSON.readTree(rankingResponse.body()).path("data").path("entries");
        Set<String> rankedOrgNames = new HashSet<>();
        rankingEntries.forEach(entry -> rankedOrgNames.add(entry.path("label").asText()));
        assertThat(rankedOrgNames).contains("Taliban", "Al-Shabaab", "Boko Haram");
        assertThat(rankedOrgNames).doesNotContain("Unknown");

        // --- POST /api/organization/kpis appends "Unattributed incidents" (AWS-UNK-001) ---
        HttpResponse<String> kpisResponse = client.postJson("/api/organization/kpis", cookie, csrfToken, "{}");
        assertThat(kpisResponse.statusCode()).isEqualTo(200);
        JsonNode kpis = JSON.readTree(kpisResponse.body()).path("data");
        assertThat(kpis.size()).isEqualTo(7);
        JsonNode unattributedKpi = kpis.get(6);
        assertThat(unattributedKpi.path("id").asText()).isEqualTo("kpiUnattributed");
        // This test's own data alone puts 1/4 of ROW_COUNT rows under "Unknown" - other test
        // classes' shared-database contributions can only add more, never fewer.
        assertThat(unattributedKpi.path("value").asLong()).isGreaterThanOrEqualTo(ROW_COUNT / 4);

        // --- POST /api/organization/drilldown: Taliban ---
        HttpResponse<String> drilldownResponse = client.postJson("/api/organization/drilldown", cookie, csrfToken,
                "{\"organizationIds\":[" + taliban.getId() + "]}");
        assertThat(drilldownResponse.statusCode()).isEqualTo(200);
        JsonNode drilldownData = JSON.readTree(drilldownResponse.body()).path("data");
        assertThat(drilldownData.path("organizationName").asText()).isEqualTo("Taliban");
        assertThat(drilldownData.path("kpis").size()).isEqualTo(6);
        assertThat(drilldownData.path("trendChart").path("id").asText()).isEqualTo("trendChart");
        int drilldownIncidents = drilldownData.path("kpis").get(0).path("value").asInt();
        assertThat(drilldownIncidents).isGreaterThan(0);
        // "Countries affected" (SAV-005), scoped to this one organization, is this
        // organization's geographic reach - this test's own data alone spans 5 countries.
        JsonNode countriesKpi = findKpi(drilldownData.path("kpis"), "kpiCountries");
        assertThat(countriesKpi.path("value").asInt()).isGreaterThan(0);

        // --- Validation: drilldown with no organizationIds ---
        HttpResponse<String> noneResponse = client.postJson("/api/organization/drilldown", cookie, csrfToken, "{}");
        assertThat(noneResponse.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(noneResponse.body()).path("error").path("code").asText()).isEqualTo("ERR-ORGANIZATION-001");

        // --- Validation: drilldown with 2 organizationIds ---
        DimOrganization alShabaab = organizationRepository.findByName("Al-Shabaab").orElseThrow();
        HttpResponse<String> tooManyResponse = client.postJson("/api/organization/drilldown", cookie, csrfToken,
                "{\"organizationIds\":[" + taliban.getId() + "," + alShabaab.getId() + "]}");
        assertThat(tooManyResponse.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(tooManyResponse.body()).path("error").path("code").asText()).isEqualTo("ERR-ORGANIZATION-001");

        // --- Validation: nonexistent organization id ---
        HttpResponse<String> notFoundResponse = client.postJson("/api/organization/drilldown", cookie, csrfToken,
                "{\"organizationIds\":[999999999]}");
        assertThat(notFoundResponse.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(notFoundResponse.body()).path("error").path("code").asText()).isEqualTo("ERR-ORGANIZATION-002");
    }

    private static JsonNode findKpi(JsonNode kpis, String id) {
        for (JsonNode kpi : kpis) {
            if (id.equals(kpi.path("id").asText())) {
                return kpi;
            }
        }
        throw new AssertionError("No KPI with id " + id);
    }

    private void importAndConfirm(String csvContent) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "organization-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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
            // A distinctive, non-overlapping year range. Not just hygiene: 2070-2077 was tried
            // first and genuinely broke ReportGenerationServiceTest, which seeds exact-count-
            // sensitive fixture rows at 2071/2072 (also avoid 2085-2087 and 2091-2094 - the
            // exact-count fixtures KpiAggregationServiceTest/CompareServiceTest/TrendServiceTest
            // use - and 2015-2022/2040s/2060s, already used by Executive/Country/Region/Report's
            // own range-based workspace tests).
            int year = 2100 + (i % 8);
            int nkill = i % 10;
            int nwound = (i * 3) % 15;
            String success = (i % 4 != 0) ? "1" : "0";

            sb.append("ORG").append(String.format("%09d", i)).append(',')
                    .append(year).append(",1,1,")
                    .append(countries[countryIdx]).append(',').append(regions[countryIdx]).append(",,,,,,,")
                    .append(attackTypes[attackIdx]).append(",Explosives,Business,")
                    .append(orgs[orgIdx]).append(',').append(nkill).append(',').append(nwound).append(',')
                    .append(success).append(",0,0,0,\n");
        }
        return sb.toString();
    }
}

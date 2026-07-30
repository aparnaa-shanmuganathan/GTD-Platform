package com.gtd.platform.workspace.executive;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * End-to-end coverage of the Executive Workspace (CLAUDE.md section 12,
 * Phase 4): imports a real (if synthetic) dataset through Phase 3's
 * pipeline, then loads the workspace page and its filter-driven dashboard
 * API against that real, confirmed data - never mock data. Also measures
 * actual dashboard-load and filter-apply time against the &le;3s/&le;2s NFR
 * targets (CLAUDE.md section 10) and reports the real numbers, per the
 * phase's explicit "measure, don't assume" instruction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExecutiveWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(ExecutiveWorkspaceIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern KPI_INCIDENTS_VALUE = Pattern.compile("\"id\":\"kpiIncidents\".*?\"value\":(\\d+)");
    private static final int ROW_COUNT = 10_000;
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
        String username = "exec-it-user-" + UUID.randomUUID();
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
    void executiveWorkspaceRendersRealDataAfterImportAndMeetsPerformanceTargets() throws Exception {
        importAndConfirm(generateCsv(ROW_COUNT));

        // --- Dashboard load (GET /workspace/executive) ---
        Instant loadStart = Instant.now();
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/executive", cookie);
        Duration loadElapsed = Duration.between(loadStart, Instant.now());

        assertThat(pageResponse.statusCode()).isEqualTo(200);
        String body = pageResponse.body();
        assertThat(body).contains("Executive Workspace");
        assertThat(body).contains("kpiIncidents");
        assertThat(body).contains("countryRanking");
        // The unfiltered total must reflect at least the real imported row count, not zero/mock
        // data - ">=" rather than "==" because this dashboard is genuinely unfiltered, and other
        // integration test classes (e.g. the Country Workspace's) share this same test database
        // and may have imported their own real rows into it too (no rollback between test
        // classes here - see PostgresIntegrationTestBase).
        assertThat(extractKpiIncidentsValue(body)).isGreaterThanOrEqualTo(ROW_COUNT);

        log.info("=== Phase 4 NFR result: dashboard load (GET /workspace/executive) took {} ms (target: <=3000ms) ===",
                loadElapsed.toMillis());

        // --- Filter apply (POST /api/executive/dashboard) ---
        String filterBody = "{\"startDate\":\"2018-01-01\",\"endDate\":\"2020-12-31\"}";
        Instant filterStart = Instant.now();
        HttpResponse<String> filterResponse = client.postJson("/api/executive/dashboard", cookie, csrfToken, filterBody);
        Duration filterElapsed = Duration.between(filterStart, Instant.now());

        assertThat(filterResponse.statusCode()).isEqualTo(200);
        JsonNode filterJson = JSON.readTree(filterResponse.body());
        assertThat(filterJson.path("success").asBoolean()).isTrue();
        JsonNode kpis = filterJson.path("data").path("kpis");
        assertThat(kpis.isArray()).isTrue();
        assertThat(kpis.size()).isEqualTo(6);
        // Filtering to a 3-year window must legitimately narrow the total below the full import.
        int filteredIncidents = kpis.get(0).path("value").asInt();
        assertThat(filteredIncidents).isLessThan(ROW_COUNT).isGreaterThan(0);

        log.info("=== Phase 4 NFR result: filter apply (POST /api/executive/dashboard) took {} ms (target: <=2000ms) ===",
                filterElapsed.toMillis());

        // --- Report stub ---
        HttpResponse<String> reportResponse = client.postJson("/api/executive/report", cookie, csrfToken, "{}");
        assertThat(reportResponse.statusCode()).isEqualTo(501);
        JsonNode reportJson = JSON.readTree(reportResponse.body());
        assertThat(reportJson.path("error").path("code").asText()).isEqualTo("ERR-REPORT-001");

        assertThat(loadElapsed)
                .withFailMessage("Dashboard load took %s, exceeding the 3s NFR target (CLAUDE.md section 10)", loadElapsed)
                .isLessThanOrEqualTo(Duration.ofSeconds(3));
        assertThat(filterElapsed)
                .withFailMessage("Filter apply took %s, exceeding the 2s NFR target (CLAUDE.md section 10)", filterElapsed)
                .isLessThanOrEqualTo(Duration.ofSeconds(2));
    }

    private void importAndConfirm(String csvContent) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "executive-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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

    /** Pulls {@code kpiIncidents}'s value out of the page's embedded {@code window.INITIAL_DASHBOARD} JSON. */
    private static long extractKpiIncidentsValue(String pageBody) {
        Matcher matcher = KPI_INCIDENTS_VALUE.matcher(pageBody);
        if (!matcher.find()) {
            throw new IllegalStateException("No kpiIncidents value found in page body");
        }
        return Long.parseLong(matcher.group(1));
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
            int year = 2015 + (i % 8);
            int nkill = i % 10;
            int nwound = (i * 3) % 15;
            String success = (i % 4 != 0) ? "1" : "0";

            sb.append("EXEC").append(String.format("%08d", i)).append(',')
                    .append(year).append(",1,1,")
                    .append(countries[countryIdx]).append(',').append(regions[countryIdx]).append(",,,,,,,")
                    .append(attackTypes[attackIdx]).append(",Explosives,Business,")
                    .append(orgs[orgIdx]).append(',').append(nkill).append(',').append(nwound).append(',')
                    .append(success).append(",0,0,0,\n");
        }
        return sb.toString();
    }
}

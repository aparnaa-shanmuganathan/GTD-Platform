package com.gtd.platform.workspace.casualty;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * End-to-end coverage of the Casualty Workspace (CLAUDE.md section 12,
 * Phase 8): imports a real (if synthetic) dataset - with a deliberate mix
 * of zero, moderate, high, and unknown casualty rows - through Phase 3's
 * pipeline, then verifies the real HTTP/controller/template wiring against
 * that data. Exact-value math (the {@code AVG}/{@code SUM}/severity-band
 * boundary correctness) is already covered by {@code
 * CasualtyAggregationServiceTest}; this test's job is proving the
 * end-to-end path - page render, merged KPI list, chart shape - actually
 * works, the same division of labor {@code WeaponWorkspaceIntegrationTest}
 * (exact math) and the other Phase 8 workspace tests (wiring) split
 * between them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CasualtyWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ROW_COUNT = 2_000;
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
        String username = "casualty-it-user-" + UUID.randomUUID();
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
    void casualtyWorkspaceRendersRealDataWithMergedKpisAndSeverityBandChart() throws Exception {
        importAndConfirm(generateCsv(ROW_COUNT));

        // --- GET /workspace/casualty renders real, unfiltered dashboard data ---
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/casualty", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        String body = pageResponse.body();
        assertThat(body).contains("Casualty Workspace");
        assertThat(body).contains("severityBandChartWrap");
        assertThat(body).contains("kpiTotalCasualties");

        // --- POST /api/casualty/dashboard, scoped to this test's own reserved year range ---
        HttpResponse<String> dashboardResponse = client.postJson("/api/casualty/dashboard", cookie, csrfToken,
                "{\"startDate\":\"2143-01-01\",\"endDate\":\"2147-12-31\"}");
        assertThat(dashboardResponse.statusCode()).isEqualTo(200);
        JsonNode data = JSON.readTree(dashboardResponse.body()).path("data");

        // --- KPIs: the usual 6 (SVC-AGG) plus this workspace's own 3 ---
        JsonNode kpis = data.path("kpis");
        assertThat(kpis.size()).isEqualTo(9);
        List<String> kpiIds = new ArrayList<>();
        kpis.forEach(kpi -> kpiIds.add(kpi.path("id").asText()));
        assertThat(kpiIds).contains("kpiIncidents", "kpiFatalities", "kpiInjuries", "kpiSuccess", "kpiCountries", "kpiOrgs",
                "kpiTotalCasualties", "kpiAvgFatalities", "kpiAvgInjuries");
        // Total casualties (combined) must be at least the individually-reported fatalities total
        // alone - a real combined sum, not a placeholder.
        long totalCasualties = findKpi(kpis, "kpiTotalCasualties").path("value").asLong();
        long totalFatalities = findKpi(kpis, "kpiFatalities").path("value").asLong();
        assertThat(totalCasualties).isGreaterThanOrEqualTo(totalFatalities);

        // --- Severity band chart: fixed display order, every band represented (this test's
        // own synthetic data deliberately spans all five, including a blank-casualty row that
        // resolves to UNKNOWN) ---
        JsonNode bandChart = data.path("severityBandChart");
        assertThat(bandChart.path("id").asText()).isEqualTo("severityBandChart");
        assertThat(bandChart.path("type").asText()).isEqualTo("BAR");
        List<String> bandCategories = new ArrayList<>();
        bandChart.path("categories").forEach(c -> bandCategories.add(c.asText()));
        assertThat(bandCategories).containsExactly("LOW", "MODERATE", "HIGH", "CATASTROPHIC", "UNKNOWN");

        long bandTotal = 0;
        for (JsonNode value : bandChart.path("series").get(0).path("data")) {
            bandTotal += value.asLong();
        }
        assertThat(bandTotal).isEqualTo(ROW_COUNT);

        // Every band this test's synthetic data deliberately includes must have at least one
        // incident - proves the CASE WHEN boundaries are wired correctly end-to-end, not just
        // that the chart has the right shape.
        for (int i = 0; i < 5; i++) {
            assertThat(bandChart.path("series").get(0).path("data").get(i).asLong())
                    .withFailMessage("Band %s had zero incidents", bandCategories.get(i))
                    .isGreaterThan(0);
        }
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
                "file", "casualty-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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

    /**
     * Cycles through casualty counts that deliberately land in every severity band: 0 (LOW),
     * 5 (MODERATE), 30 (HIGH), 80 (CATASTROPHIC), and a blank/blank row (UNKNOWN) - one full
     * cycle every 5 rows, so with {@code ROW_COUNT} a multiple of 5 every band is guaranteed at
     * least one incident.
     */
    private static String generateCsv(int rowCount) {
        String[] countries = { "United States", "Canada", "Iraq", "Afghanistan", "Nigeria" };
        String[] regions = { "North America", "North America", "Middle East & North Africa", "South Asia", "Sub-Saharan Africa" };
        String[] orgs = { "Taliban", "Al-Shabaab", "Unknown", "Boko Haram" };
        int[] casualtyCycle = { 0, 5, 30, 80, -1 }; // -1 signals "blank/unknown" for this row

        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (int i = 0; i < rowCount; i++) {
            int countryIdx = i % countries.length;
            int orgIdx = i % orgs.length;
            // A distinctive, non-overlapping year range within the reserved 2140s block - see
            // CLAUDE.md section 12's Phase 8 decisions. 2143-2147 (not 2140) so this test's own
            // rows never land in the same year CasualtyAggregationServiceTest's hand-computed
            // fixture uses (2140), even though both scope their own assertions with an explicit
            // date filter and so wouldn't actually collide either way.
            int year = 2143 + (i % 5);
            int casualty = casualtyCycle[i % casualtyCycle.length];
            // Both nkill and nwound must be blank for a row to land in the UNKNOWN band -
            // SeverityBandClassifier only classifies UNKNOWN when *both* are null, and a lone
            // blank nkill next to a real nwound=0 would classify as LOW (total 0), not UNKNOWN.
            String nkill = casualty < 0 ? "" : String.valueOf(casualty);
            String nwound = casualty < 0 ? "" : "0";
            String success = "1";

            sb.append("CAS").append(String.format("%09d", i)).append(',')
                    .append(year).append(",1,1,")
                    .append(countries[countryIdx]).append(',').append(regions[countryIdx]).append(",,,,,,,")
                    .append("Bombing/Explosion,Explosives,Business,")
                    .append(orgs[orgIdx]).append(',').append(nkill).append(',').append(nwound).append(',')
                    .append(success).append(",0,0,0,\n");
        }
        return sb.toString();
    }
}

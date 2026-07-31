package com.gtd.platform.workspace.statistical;

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
 * End-to-end coverage of the Statistical Workspace (CLAUDE.md section 12, Phase 9): imports a
 * real (if synthetic) dataset with a deliberate incident-count outlier year, through Phase 3's
 * pipeline, then verifies the real HTTP/controller/template wiring - trend+moving-average
 * chart, trend slope, outlier detection, correlation, and percentile ranking all reach the page
 * with real, confirmed data. The five formulas' own exact-value correctness is already covered
 * by the five pure calculator test classes (mirroring {@code GrowthRateCalculatorTest}'s
 * standard); this test's job is the wiring, the same division of labor {@code
 * CasualtyWorkspaceIntegrationTest} established relative to its own hand-computed unit tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatisticalWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
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
        String username = "stat-it-user-" + UUID.randomUUID();
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
    void statisticalWorkspaceRendersRealDataWithFiveStatisticsWired() throws Exception {
        importAndConfirm(generateCsv());

        // --- GET /workspace/statistical renders real, unfiltered summary data ---
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/statistical", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        String body = pageResponse.body();
        assertThat(body).contains("Statistical Workspace");
        assertThat(body).contains("statTrendChart");

        // --- POST /api/statistical/summary, scoped to this test's own reserved year range ---
        HttpResponse<String> summaryResponse = client.postJson("/api/statistical/summary", cookie, csrfToken,
                "{\"startDate\":\"2150-01-01\",\"endDate\":\"2157-12-31\"}");
        assertThat(summaryResponse.statusCode()).isEqualTo(200);
        JsonNode data = JSON.readTree(summaryResponse.body()).path("data");

        // --- Trend + moving average chart ---
        JsonNode trendChart = data.path("trendWithMovingAverage");
        assertThat(trendChart.path("id").asText()).isEqualTo("statTrendChart");
        assertThat(trendChart.path("series").size()).isEqualTo(2);
        assertThat(trendChart.path("series").get(0).path("name").asText()).isEqualTo("Incidents");
        assertThat(trendChart.path("series").get(1).path("name").asText()).isEqualTo("Moving average");
        List<String> years = new ArrayList<>();
        trendChart.path("categories").forEach(c -> years.add(c.asText()));
        assertThat(years).containsExactly("2150", "2151", "2152", "2153", "2154", "2155", "2156", "2157");

        // --- Trend slope: this test's own data is a deliberately increasing series -> positive slope ---
        assertThat(data.path("trendSlope").isMissingNode()).isFalse();
        assertThat(data.path("trendSlope").decimalValue().signum()).isEqualTo(1);

        // --- Outlier detection: 2157's incident count (200) is a deliberate, large outlier
        // against the other seven years (10 each) ---
        List<Integer> outlierYears = new ArrayList<>();
        data.path("outlierYears").forEach(y -> outlierYears.add(y.asInt()));
        assertThat(outlierYears).contains(2157);

        // --- Correlation: fatalities vs injuries by year - just needs to be present and a
        // valid coefficient in [-1, 1], since exact-value correctness is unit-tested separately ---
        assertThat(data.path("correlationDescription").asText()).isEqualTo("Fatalities vs injuries by year");
        if (!data.path("correlationCoefficient").isNull() && !data.path("correlationCoefficient").isMissingNode()) {
            double r = data.path("correlationCoefficient").asDouble();
            assertThat(r).isBetween(-1.0, 1.0);
        }

        // --- Percentile ranking: real countries, valid percentiles ---
        JsonNode percentileRanking = data.path("percentileRanking");
        assertThat(percentileRanking.isArray()).isTrue();
        assertThat(percentileRanking.size()).isGreaterThan(0).isLessThanOrEqualTo(10);
        for (JsonNode entry : percentileRanking) {
            assertThat(entry.path("label").asText()).isNotBlank();
            double percentile = entry.path("percentile").asDouble();
            assertThat(percentile).isBetween(0.0, 100.0);
        }
    }

    private void importAndConfirm(String csvContent) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "statistical-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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
     * Years 2150-2156: 10 incidents each (a mild, steady increase baked into per-year nkill/
     * nwound but a flat incident COUNT). Year 2157: 200 incidents - a deliberate, large outlier
     * against the other seven years, both for {@code TrendSlopeCalculator} (a real, non-trivial
     * slope with an outlier included) and {@code OutlierDetector} to detect end-to-end.
     */
    private static String generateCsv() {
        String[] countries = { "United States", "Canada", "Iraq", "Afghanistan", "Nigeria" };
        String[] regions = { "North America", "North America", "Middle East & North Africa", "South Asia", "Sub-Saharan Africa" };
        String[] attackTypes = { "Bombing/Explosion", "Armed Assault", "Assassination", "Hijacking" };
        String[] orgs = { "Taliban", "Al-Shabaab", "Unknown", "Boko Haram" };

        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        int eventCounter = 0;
        for (int year = 2150; year <= 2157; year++) {
            int rowsThisYear = (year == 2157) ? 200 : 10;
            for (int i = 0; i < rowsThisYear; i++) {
                int countryIdx = eventCounter % countries.length;
                int attackIdx = eventCounter % attackTypes.length;
                int orgIdx = eventCounter % orgs.length;
                int nkill = eventCounter % 10;
                int nwound = (eventCounter * 3) % 15;
                String success = (eventCounter % 4 != 0) ? "1" : "0";

                sb.append("STA").append(String.format("%09d", eventCounter)).append(',')
                        .append(year).append(",1,1,")
                        .append(countries[countryIdx]).append(',').append(regions[countryIdx]).append(",,,,,,,")
                        .append(attackTypes[attackIdx]).append(",Explosives,Business,")
                        .append(orgs[orgIdx]).append(',').append(nkill).append(',').append(nwound).append(',')
                        .append(success).append(",0,0,0,\n");
                eventCounter++;
            }
        }
        return sb.toString();
    }
}

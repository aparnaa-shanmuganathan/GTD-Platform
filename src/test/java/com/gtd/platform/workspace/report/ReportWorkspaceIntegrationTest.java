package com.gtd.platform.workspace.report;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * End-to-end coverage of the Report Workspace (CLAUDE.md section 12, Phase
 * 6): imports real (if synthetic) data through Phase 3's pipeline, then
 * exercises the full generate -&gt; persist -&gt; list -&gt; download flow for both
 * PDF and CSV against the real HTTP endpoints - never mock data. Also
 * measures actual report generation time against the &le;10s NFR target
 * (CLAUDE.md section 10) and reports the real number, per this project's
 * established "measure, don't assume" convention (Phases 4/5's own NFR
 * tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(ReportWorkspaceIntegrationTest.class);
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
        String username = "report-it-user-" + UUID.randomUUID();
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
    void fullGeneratePersistListDownloadFlowForBothFormats() throws Exception {
        importAndConfirm(generateCsv(ROW_COUNT));

        // --- GET /workspace/report renders ---
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/report", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        assertThat(pageResponse.body()).contains("Report Workspace");

        // --- Generate a PDF report, measure against the <=10s NFR ---
        String generatePdfBody = "{\"format\":\"PDF\",\"reportName\":\"IT PDF Report\",\"sourceWorkspace\":\"Integration Test\"}";
        Instant genStart = Instant.now();
        HttpResponse<String> pdfGenResponse = client.postJson("/api/report/generate", cookie, csrfToken, generatePdfBody);
        Duration genElapsed = Duration.between(genStart, Instant.now());

        assertThat(pdfGenResponse.statusCode()).isEqualTo(200);
        JsonNode pdfGenJson = JSON.readTree(pdfGenResponse.body());
        assertThat(pdfGenJson.path("success").asBoolean()).isTrue();
        assertThat(pdfGenJson.path("data").path("status").asText()).isEqualTo("GENERATED");
        long pdfReportId = pdfGenJson.path("data").path("id").asLong();

        log.info("=== Phase 6 NFR result: report generation (POST /api/report/generate, PDF) took {} ms (target: <=10000ms) ===",
                genElapsed.toMillis());
        assertThat(genElapsed)
                .withFailMessage("Report generation took %s, exceeding the 10s NFR target (CLAUDE.md section 10)", genElapsed)
                .isLessThanOrEqualTo(Duration.ofSeconds(10));

        // --- Generate a CSV report too ---
        String generateCsvBody = "{\"format\":\"CSV\",\"reportName\":\"IT CSV Report\",\"sourceWorkspace\":\"Integration Test\"}";
        HttpResponse<String> csvGenResponse = client.postJson("/api/report/generate", cookie, csrfToken, generateCsvBody);
        assertThat(csvGenResponse.statusCode()).isEqualTo(200);
        JsonNode csvGenJson = JSON.readTree(csvGenResponse.body());
        long csvReportId = csvGenJson.path("data").path("id").asLong();

        // --- History includes both ---
        HttpResponse<String> historyResponse = client.postJson("/api/report/history", cookie, csrfToken, "{}");
        assertThat(historyResponse.statusCode()).isEqualTo(200);
        JsonNode historyJson = JSON.readTree(historyResponse.body());
        assertThat(historyJson.path("success").asBoolean()).isTrue();
        var ids = historyJson.path("data").findValuesAsText("id");
        assertThat(ids).contains(String.valueOf(pdfReportId), String.valueOf(csvReportId));

        // --- Download the PDF ---
        HttpResponse<byte[]> pdfDownload = client.postJsonForBytes("/api/report/download", cookie, csrfToken,
                "{\"reportId\":" + pdfReportId + "}");
        assertThat(pdfDownload.statusCode()).isEqualTo(200);
        assertThat(pdfDownload.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/pdf"));
        assertThat(pdfDownload.body()).startsWith('%', 'P', 'D', 'F');

        // --- Download the CSV ---
        HttpResponse<byte[]> csvDownload = client.postJsonForBytes("/api/report/download", cookie, csrfToken,
                "{\"reportId\":" + csvReportId + "}");
        assertThat(csvDownload.statusCode()).isEqualTo(200);
        assertThat(csvDownload.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("text/csv"));
        String csvText = new String(csvDownload.body(), StandardCharsets.UTF_8);
        assertThat(csvText).contains("IT CSV Report");
        assertThat(csvText).contains("Key Performance Indicators");

        // --- Downloading a nonexistent report ---
        HttpResponse<String> notFoundResponse = client.postJson("/api/report/download", cookie, csrfToken,
                "{\"reportId\":999999999}");
        assertThat(notFoundResponse.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(notFoundResponse.body()).path("error").path("code").asText()).isEqualTo("ERR-REPORT-004");
    }

    private void importAndConfirm(String csvContent) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "report-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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
            // A distinctive, non-overlapping year range (Executive: 2015-2022, Country: 2040-2047).
            int year = 2060 + (i % 8);
            int nkill = i % 10;
            int nwound = (i * 3) % 15;
            String success = (i % 4 != 0) ? "1" : "0";

            sb.append("REPT").append(String.format("%08d", i)).append(',')
                    .append(year).append(",1,1,")
                    .append(countries[countryIdx]).append(',').append(regions[countryIdx]).append(",,,,,,,")
                    .append(attackTypes[attackIdx]).append(",Explosives,Business,")
                    .append(orgs[orgIdx]).append(',').append(nkill).append(',').append(nwound).append(',')
                    .append(success).append(",0,0,0,\n");
        }
        return sb.toString();
    }
}

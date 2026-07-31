package com.gtd.platform.workspace.dataquality;

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
 * End-to-end coverage of the Data Quality Center (Phase 10): imports a real
 * (if synthetic) batch deliberately containing two distinct rejection
 * reasons (missing eventid, unresolvable country) through Phase 3's real
 * pipeline, then verifies the batch list, the rejection-reason breakdown,
 * and the raw rejected-row detail all surface real data end-to-end.
 * Publicly viewable throughout - no authentication used anywhere in this
 * test except to perform the upload itself (still gated by the
 * pre-existing {@code /api/import/**} rule, unrelated to this workspace).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataQualityWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int YEAR = 2201;
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
        String username = "dq-it-user-" + UUID.randomUUID();
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
    void dataQualityCenterSurfacesRealRejectionBreakdown() throws Exception {
        long batchId = importAndAwaitConfirmation(generateCsv());

        // --- GET /workspace/dataquality renders publicly, no auth ---
        HttpResponse<String> anonymousLoginPage = client.getWithCookie("/login", "");
        String anonymousCookie = sessionCookieFrom(anonymousLoginPage);
        String anonymousCsrf = FormLoginClient.extractCsrfToken(anonymousLoginPage.body());

        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/dataquality", anonymousCookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        assertThat(pageResponse.body()).contains("Data Quality Center");

        // --- POST /api/dataquality/batches is public ---
        HttpResponse<String> batchesResponse = client.postJson("/api/dataquality/batches", anonymousCookie, anonymousCsrf, "{}");
        assertThat(batchesResponse.statusCode()).isEqualTo(200);
        JsonNode batches = JSON.readTree(batchesResponse.body()).path("data");
        boolean found = false;
        for (JsonNode batch : batches) {
            if (batch.path("id").asLong() == batchId) {
                found = true;
                assertThat(batch.path("rowsRejected").asInt()).isEqualTo(4);
            }
        }
        assertThat(found).withFailMessage("Batch %s not found in batch list", batchId).isTrue();

        // --- POST /api/dataquality/batch-detail is public and shows both real rejection reasons ---
        HttpResponse<String> detailResponse = client.postJson("/api/dataquality/batch-detail", anonymousCookie, anonymousCsrf,
                "{\"importBatchId\":" + batchId + "}");
        assertThat(detailResponse.statusCode()).isEqualTo(200);
        JsonNode detail = JSON.readTree(detailResponse.body()).path("data");

        assertThat(detail.path("batch").path("id").asLong()).isEqualTo(batchId);
        assertThat(detail.path("totalRejectedRows").asLong()).isEqualTo(4);

        Set<String> reasonsSeen = new HashSet<>();
        long breakdownTotal = 0;
        for (JsonNode entry : detail.path("breakdown")) {
            reasonsSeen.add(entry.path("rejectionReason").asText());
            breakdownTotal += entry.path("count").asLong();
        }
        assertThat(reasonsSeen).contains("MISSING_OR_INVALID_EVENTID", "UNRESOLVABLE_COUNTRY_OR_REGION");
        assertThat(breakdownTotal).isEqualTo(4);

        assertThat(detail.path("rejectedRows").size()).isEqualTo(4);
        for (JsonNode row : detail.path("rejectedRows")) {
            assertThat(row.path("rejectionReason").asText()).isNotBlank();
        }

        // --- Unknown batch id -> 404 (ERR-IMPORT-004, reused rather than a new code) ---
        HttpResponse<String> notFoundResponse = client.postJson("/api/dataquality/batch-detail", anonymousCookie, anonymousCsrf,
                "{\"importBatchId\":999999999}");
        assertThat(notFoundResponse.statusCode()).isEqualTo(404);
    }

    private static String sessionCookieFrom(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(h -> h.startsWith("SESSION="))
                .map(h -> h.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SESSION cookie in response"));
    }

    private long importAndAwaitConfirmation(String csvContent) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "dataquality-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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
        return batchId;
    }

    /**
     * 10 valid rows, 2 with a blank eventid (MISSING_OR_INVALID_EVENTID) and 2 with a blank
     * country_txt/region_txt (UNRESOLVABLE_COUNTRY_OR_REGION) - 4 total rejected rows across
     * exactly two distinct reasons, so the breakdown assertion is exact and hand-computable.
     */
    private static String generateCsv() {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (int i = 0; i < 10; i++) {
            sb.append("DQV").append(String.format("%09d", i)).append(',')
                    .append(YEAR).append(",1,1,")
                    .append("United States,North America,,,,,,,")
                    .append("Bombing/Explosion,Explosives,Business,")
                    .append("Unknown,2,3,1,0,0,0,\n");
        }
        for (int i = 0; i < 2; i++) {
            // Blank eventid - rejected as MISSING_OR_INVALID_EVENTID.
            sb.append(',')
                    .append(YEAR).append(",1,1,")
                    .append("United States,North America,,,,,,,")
                    .append("Bombing/Explosion,Explosives,Business,")
                    .append("Unknown,2,3,1,0,0,0,\n");
        }
        for (int i = 0; i < 2; i++) {
            // Blank country_txt/region_txt - rejected as UNRESOLVABLE_COUNTRY_OR_REGION.
            sb.append("DQU").append(String.format("%09d", i)).append(',')
                    .append(YEAR).append(",1,1,")
                    .append(",,,,,,,,")
                    .append("Bombing/Explosion,Explosives,Business,")
                    .append("Unknown,2,3,1,0,0,0,\n");
        }
        return sb.toString();
    }
}

package com.gtd.platform.service.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.testsupport.FormLoginClient;
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
 * Phase 7 hardening pass: fills in IMPORT endpoint validation paths that
 * only ever got happy-path coverage before now (CLAUDE.md section 12) -
 * non-CSV/empty file rejection, unknown-batch-id lookups, and confirming a
 * batch that isn't awaiting confirmation. Oversized-file rejection
 * (ERR-IMPORT-002, {@code MaxUploadSizeExceededException}) is covered
 * separately in {@link ImportOversizedFileTest}, which needs its own
 * Spring context with a much smaller {@code max-file-size} - the real
 * 250MB limit is impractical to exceed in a fast test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportValidationIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSV_HEADER = "eventid,iyear,imonth,iday,country_txt,region_txt,attacktype1_txt,"
            + "weaptype1_txt,targtype1_txt,gname,nkill,nwound";

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
        String username = "import-validation-it-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";

        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        adminUserRepository.save(user);

        var loginResult = client.login(username, password);
        assertThat(loginResult.isRedirect()).isTrue();
        cookie = loginResult.sessionCookie();
        csrfToken = client.csrfTokenFor(cookie);
    }

    @Test
    void uploadRejectsNonCsvFileExtension() throws Exception {
        HttpResponse<String> response = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "not-a-csv.txt", "eventid,iyear\nX00000001,2020\n".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of("mode", "APPEND"));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("error").path("code").asText()).isEqualTo("ERR-IMPORT-001");
    }

    @Test
    void uploadRejectsEmptyFile() throws Exception {
        HttpResponse<String> response = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "empty.csv", new byte[0], "text/csv", Map.of("mode", "APPEND"));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("error").path("code").asText()).isEqualTo("ERR-IMPORT-001");
    }

    @Test
    void statusForUnknownBatchIdReturns404() throws Exception {
        HttpResponse<String> response = client.postJson("/api/import/status", cookie, csrfToken,
                "{\"importBatchId\":999999999}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asText()).isEqualTo("ERR-IMPORT-004");
    }

    @Test
    void confirmForUnknownBatchIdReturns404() throws Exception {
        HttpResponse<String> response = client.postJson("/api/import/confirm", cookie, csrfToken,
                "{\"importBatchId\":999999999}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asText()).isEqualTo("ERR-IMPORT-004");
    }

    @Test
    void confirmingAnAlreadyCompletedBatchReturns409() throws Exception {
        String csv = CSV_HEADER + "\nV00000001,2020,1,1,United States,North America,Bombing/Explosion,"
                + "Explosives,Business,Taliban,1,1\n";

        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "confirm-twice.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv",
                Map.of("mode", "APPEND"));
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        long batchId = JSON.readTree(uploadResponse.body()).path("data").path("importBatchId").asLong();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        String status = "PENDING";
        while (Instant.now().isBefore(deadline) && !status.equals("AWAITING_CONFIRMATION")) {
            HttpResponse<String> statusResponse = client.postJson("/api/import/status", cookie, csrfToken,
                    "{\"importBatchId\":" + batchId + "}");
            status = JSON.readTree(statusResponse.body()).path("data").path("status").asText();
            if (status.equals("FAILED")) {
                throw new AssertionError("Import batch " + batchId + " failed");
            }
            Thread.sleep(150);
        }
        assertThat(status).isEqualTo("AWAITING_CONFIRMATION");

        HttpResponse<String> firstConfirm = client.postJson("/api/import/confirm", cookie, csrfToken,
                "{\"importBatchId\":" + batchId + "}");
        assertThat(firstConfirm.statusCode()).isEqualTo(200);

        // The batch is now COMPLETED - confirming it again must be rejected, not silently re-run.
        HttpResponse<String> secondConfirm = client.postJson("/api/import/confirm", cookie, csrfToken,
                "{\"importBatchId\":" + batchId + "}");
        assertThat(secondConfirm.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(secondConfirm.body()).path("error").path("code").asText()).isEqualTo("ERR-IMPORT-005");
    }
}

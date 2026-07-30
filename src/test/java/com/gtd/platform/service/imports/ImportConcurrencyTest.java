package com.gtd.platform.service.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.ImportBatchRepository;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Proves AWS-IMPORT-001's single-advisory-lock guarantee (CLAUDE.md section
 * 8): a second import attempt while one is running is rejected, not queued
 * silently or allowed to corrupt data. Two complementary tests:
 *
 * <ul>
 *   <li>{@link #uploadIsRejectedWhileAnotherImportHoldsTheLock} - deterministic,
 *   no timing dependency: acquires {@link ImportAdvisoryLockService}'s lock
 *   directly (exactly what a real in-flight import holds) and asserts an
 *   HTTP upload attempted while it's held gets {@code 409}/{@code ERR-IMPORT-003}
 *   with no {@code import_batch} row created for the rejected attempt.</li>
 *   <li>{@link #concurrentRealUploadsYieldExactlyOneAcceptedAttempt} - a
 *   real second upload sent while a large (20k-row) real upload's background
 *   job is confirmed still in flight (polled via {@code /api/import/status},
 *   not assumed from wall-clock timing), asserting it is rejected the same
 *   way.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportConcurrencyTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSV_HEADER = "eventid,iyear,imonth,iday,country_txt,region_txt,"
            + "attacktype1_txt,weaptype1_txt,targtype1_txt,gname,nkill,nwound";

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ImportAdvisoryLockService lockService;
    @Autowired
    private ImportBatchRepository importBatchRepository;

    private FormLoginClient client;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = new FormLoginClient("http://localhost:" + port);
        String username = "concurrency-it-user-" + UUID.randomUUID();
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
    void uploadIsRejectedWhileAnotherImportHoldsTheLock() throws Exception {
        long batchCountBefore = importBatchRepository.count();

        ImportAdvisoryLockService.Handle heldLock = lockService.tryAcquire().orElseThrow();
        try {
            HttpResponse<String> response = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                    "file", "blocked.csv", generateCsv(5).getBytes(StandardCharsets.UTF_8), "text/csv",
                    Map.of("mode", "APPEND"));

            assertThat(response.statusCode()).isEqualTo(409);
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.path("success").asBoolean()).isFalse();
            assertThat(body.path("error").path("code").asText()).isEqualTo("ERR-IMPORT-003");
            assertThat(importBatchRepository.count()).isEqualTo(batchCountBefore);
        } finally {
            heldLock.close();
        }

        // Lock released - a subsequent attempt must succeed normally.
        HttpResponse<String> afterRelease = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "unblocked.csv", generateCsv(5).getBytes(StandardCharsets.UTF_8), "text/csv",
                Map.of("mode", "APPEND"));
        assertThat(afterRelease.statusCode()).isEqualTo(202);

        // Wait for this import to finish and release the lock before the
        // test returns, so it can't leak a held lock into the next test
        // against this same shared database.
        long afterReleaseBatchId = JSON.readTree(afterRelease.body()).path("data").path("importBatchId").asLong();
        awaitTerminalStatus(afterReleaseBatchId);
    }

    @Test
    void concurrentRealUploadsYieldExactlyOneAcceptedAttempt() throws Exception {
        byte[] largeCsv = generateCsv(20_000).getBytes(StandardCharsets.UTF_8);
        byte[] smallCsv = generateCsv(5).getBytes(StandardCharsets.UTF_8);

        // A literal two-thread race here (both requests fired at once, racing
        // to be first to reach pg_try_advisory_lock) is inherently flaky: if
        // the small upload's negligible work lets it acquire and release the
        // lock before the large upload's request even finishes writing its
        // staged file, both attempts can legitimately observe the lock free
        // and both get 202. So instead: send the large upload first and wait
        // for its response - which only arrives once ImportOrchestrationService
        // has already acquired the lock synchronously - then poll its status
        // to confirm the background job is genuinely still in flight (a
        // non-terminal state) before sending the second upload. That proves
        // the same guarantee (a second attempt while an import is running is
        // rejected) without betting on wall-clock scheduling.
        HttpResponse<String> firstResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "large.csv", largeCsv, "text/csv", Map.of("mode", "APPEND"));
        assertThat(firstResponse.statusCode()).isEqualTo(202);
        long firstBatchId = JSON.readTree(firstResponse.body()).path("data").path("importBatchId").asLong();

        assertThat(pollStatus(firstBatchId)).isIn("PENDING", "STAGING", "NORMALIZING");

        HttpResponse<String> secondResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "small.csv", smallCsv, "text/csv", Map.of("mode", "APPEND"));

        assertThat(secondResponse.statusCode()).isEqualTo(409);
        JsonNode rejectedBody = JSON.readTree(secondResponse.body());
        assertThat(rejectedBody.path("success").asBoolean()).isFalse();
        assertThat(rejectedBody.path("error").path("code").asText()).isEqualTo("ERR-IMPORT-003");

        // Wait for the large import to actually finish and release the lock
        // before the test returns - otherwise it leaks a held advisory lock
        // into whichever test runs next against this same shared database.
        awaitTerminalStatus(firstBatchId);
    }

    private String pollStatus(long importBatchId) throws Exception {
        HttpResponse<String> response = client.postJson("/api/import/status", cookie, csrfToken,
                "{\"importBatchId\":" + importBatchId + "}");
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path("data").path("status").asText();
    }

    private void awaitTerminalStatus(long importBatchId) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            String status = pollStatus(importBatchId);
            if (status.equals("AWAITING_CONFIRMATION") || status.equals("FAILED")) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Import batch " + importBatchId + " did not reach a terminal status in time");
    }

    private static String generateCsv(int rowCount) {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (int i = 0; i < rowCount; i++) {
            sb.append("CONC").append(String.format("%08d", i)).append(",2020,1,1,")
                    .append("United States,North America,Bombing/Explosion,Explosives,Business,Taliban,1,1\n");
        }
        return sb.toString();
    }
}

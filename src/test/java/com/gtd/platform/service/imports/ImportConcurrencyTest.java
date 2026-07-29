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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 *   genuine two-thread race between two real uploads, using a CSV large
 *   enough (20k generated rows) that the first import's processing window
 *   comfortably outlasts the moment the second request arrives, so the
 *   result isn't timing-flaky.</li>
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
    }

    @Test
    void concurrentRealUploadsYieldExactlyOneAcceptedAttempt() throws Exception {
        byte[] largeCsv = generateCsv(20_000).getBytes(StandardCharsets.UTF_8);
        byte[] smallCsv = generateCsv(5).getBytes(StandardCharsets.UTF_8);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            Future<HttpResponse<String>> first = pool.submit(() -> {
                startGate.await();
                return client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                        "file", "large.csv", largeCsv, "text/csv", Map.of("mode", "APPEND"));
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                startGate.await();
                return client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                        "file", "small.csv", smallCsv, "text/csv", Map.of("mode", "APPEND"));
            });

            startGate.countDown();
            HttpResponse<String> firstResponse = first.get(30, TimeUnit.SECONDS);
            HttpResponse<String> secondResponse = second.get(30, TimeUnit.SECONDS);

            List<Integer> statusCodes = List.of(firstResponse.statusCode(), secondResponse.statusCode());
            assertThat(statusCodes).containsExactlyInAnyOrder(202, 409);

            HttpResponse<String> rejected = (firstResponse.statusCode() == 409) ? firstResponse : secondResponse;
            JsonNode rejectedBody = JSON.readTree(rejected.body());
            assertThat(rejectedBody.path("error").path("code").asText()).isEqualTo("ERR-IMPORT-003");
        } finally {
            pool.shutdownNow();
        }
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

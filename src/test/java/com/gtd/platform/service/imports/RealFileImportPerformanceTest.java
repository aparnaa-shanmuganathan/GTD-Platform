package com.gtd.platform.service.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.MaterializedViewStatusRepository;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The real acceptance test for CLAUDE.md section 10's import NFR: the actual
 * GTD CSV ({@code globalterrorismdb_0718dist.csv}, ~155MB / ~181,691 rows)
 * imported end to end in &le;5 minutes.
 *
 * <p><strong>Runs only if the file is actually present</strong> - it is not
 * committed to the repo (large, licensed data; see {@code .gitignore}).
 * Resolved from the {@code GTD_REAL_CSV_PATH} environment variable if set,
 * otherwise {@code <project root>/data/globalterrorismdb_0718dist.csv}. If
 * neither exists, the test skips itself with a clear message via
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue} rather than failing -
 * this file's absence in a given environment is expected, not a bug (see
 * CLAUDE.md section 12).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealFileImportPerformanceTest extends PostgresIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(RealFileImportPerformanceTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_FILENAME = "globalterrorismdb_0718dist.csv";
    private static final Duration NFR_TARGET = Duration.ofMinutes(5);
    private static final Duration POLL_SAFETY_CEILING = Duration.ofMinutes(8);

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private DatasetVersionRepository datasetVersionRepository;
    @Autowired
    private MaterializedViewStatusRepository materializedViewStatusRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FormLoginClient client;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = new FormLoginClient("http://localhost:" + port);
        String username = "perf-it-user-" + UUID.randomUUID();
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
    void importsTheRealGtdFileWithinFiveMinutes() throws Exception {
        Path csvPath = resolveRealCsvPath();
        assumeTrue(csvPath != null && Files.isRegularFile(csvPath),
                "Real GTD CSV not found (checked GTD_REAL_CSV_PATH and ./data/" + DEFAULT_FILENAME + ") - "
                        + "skipping the real-file performance test. See README for how to provide it.");

        long fileSizeBytes = Files.size(csvPath);
        log.info("Found real GTD CSV at {} ({} MB)", csvPath, fileSizeBytes / 1024 / 1024);

        Instant uploadStart = Instant.now();
        byte[] fileBytes = Files.readAllBytes(csvPath);
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", DEFAULT_FILENAME, fileBytes, "text/csv", java.util.Map.of("mode", "APPEND"));
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        JsonNode uploadBody = JSON.readTree(uploadResponse.body());
        long batchId = uploadBody.path("data").path("importBatchId").asLong();

        String terminalStatus = pollUntilTerminal(batchId, POLL_SAFETY_CEILING);
        assertThat(terminalStatus).isEqualTo("AWAITING_CONFIRMATION");

        HttpResponse<String> confirmResponse = client.postJson("/api/import/confirm", cookie, csrfToken,
                "{\"importBatchId\":" + batchId + "}");
        assertThat(confirmResponse.statusCode()).isEqualTo(200);

        Duration elapsed = Duration.between(uploadStart, Instant.now());

        JsonNode finalStatus = JSON.readTree(client.postJson("/api/import/status", cookie, csrfToken,
                "{\"importBatchId\":" + batchId + "}").body()).path("data");
        int totalRows = finalStatus.path("totalRows").asInt();
        int rowsRejected = finalStatus.path("rowsRejected").asInt();
        int rowsAccepted = totalRows - rowsRejected;

        log.info("=== Phase 3 NFR result: import of {} ({} MB) took {} (target: {}) ===",
                DEFAULT_FILENAME, fileSizeBytes / 1024 / 1024, elapsed, NFR_TARGET);
        log.info("=== Rows: total={}, accepted={}, rejected={} ===", totalRows, rowsAccepted, rowsRejected);

        // Sanity, not exact-match: the real file's row count/content can
        // shift slightly across GTD releases, and Phase 1 already flagged
        // the staging column list as unverified against a real header.
        assertThat(totalRows).isGreaterThan(150_000);
        assertThat(rowsAccepted).isGreaterThan((int) (totalRows * 0.8));

        // dataset_version.row_count is, by design (CLAUDE.md section 12, Phase 1 decisions), the
        // CURRENT TOTAL across the whole fact_incident table at activation time - not "rows this
        // import touched" - so it must be compared against the table's real total, not rowsAccepted
        // (this file's own accepted-row count). This table is shared with every other test class in
        // the same run (no rollback between them - PostgresIntegrationTestBase), so it will already
        // hold rows from other tests' own imports too.
        DatasetVersion active = datasetVersionRepository.findByActiveTrue().orElseThrow();
        Long actualFactIncidentTotal = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fact_incident", Long.class);
        assertThat(active.getRowCount()).isEqualTo(actualFactIncidentTotal.intValue());
        assertThat(datasetVersionRepository.findAll().stream().filter(DatasetVersion::isActive)).hasSize(1);

        assertThat(materializedViewStatusRepository.findAll())
                .allSatisfy(mv -> assertThat(mv.getLastRefreshStatus().name()).isEqualTo("SUCCESS"));
        Long mvCountryRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mv_country_year_agg", Long.class);
        assertThat(mvCountryRows).isGreaterThan(0);

        assertThat(elapsed)
                .withFailMessage("Import took %s, exceeding the %s NFR target (CLAUDE.md section 10) - "
                        + "see the test log above for the phase this ran against and row counts", elapsed, NFR_TARGET)
                .isLessThanOrEqualTo(NFR_TARGET);
    }

    private Path resolveRealCsvPath() {
        String envPath = System.getenv("GTD_REAL_CSV_PATH");
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath);
        }
        return Path.of(System.getProperty("user.dir"), "data", DEFAULT_FILENAME);
    }

    private String pollUntilTerminal(long batchId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = client.postJson("/api/import/status", cookie, csrfToken,
                    "{\"importBatchId\":" + batchId + "}");
            JsonNode body = JSON.readTree(response.body());
            String status = body.path("data").path("status").asText();
            if (status.equals("AWAITING_CONFIRMATION") || status.equals("FAILED")) {
                if (status.equals("FAILED")) {
                    throw new AssertionError("Import batch " + batchId + " failed: "
                            + body.path("data").path("errorMessage").asText());
                }
                return status;
            }
            log.info("Import batch {} status={} rowsStaged={} rowsRejected={}", batchId,
                    status, body.path("data").path("rowsStaged").asInt(), body.path("data").path("rowsRejected").asInt());
            Thread.sleep(2000);
        }
        throw new AssertionError("Import batch " + batchId + " did not reach a terminal state within " + timeout);
    }
}

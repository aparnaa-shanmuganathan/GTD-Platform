package com.gtd.platform.service.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.FactIncidentRepository;
import com.gtd.platform.shared.db.ImportBatch;
import com.gtd.platform.shared.db.ImportBatch.ImportStatus;
import com.gtd.platform.shared.db.ImportBatchRepository;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Phase 7 hardening pass, item 4: simulates a crash partway through
 * {@link DatasetVersionActivationService#activate} - CLAUDE.md section 8's
 * "exactly one active dataset_version" guarantee lives entirely in that one
 * {@code @Transactional} method (deactivate the old version, activate the
 * new one, mark the batch {@code COMPLETED}) - and confirms Spring's
 * transaction rollback actually restores the pre-crash state rather than
 * leaving a half-applied activation: no dataset_version left with zero
 * active rows, no dataset_version left with two, and the import batch
 * still {@code AWAITING_CONFIRMATION} (not incorrectly marked
 * {@code COMPLETED}) so a retry is still possible.
 *
 * <p>"Simulates a crash" here means throwing from a real, Spring-managed
 * bean's dependency ({@link FactIncidentRepository#count()}, the last real
 * repository call before the batch is marked {@code COMPLETED}) - not a
 * manually-constructed instance, since {@code @Transactional} only takes
 * effect through Spring's AOP proxy; a hand-built instance would silently
 * skip the very rollback behavior this test exists to verify.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportCrashAtomicityTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSV_HEADER = "eventid,iyear,imonth,iday,country_txt,region_txt,attacktype1_txt,"
            + "weaptype1_txt,targtype1_txt,gname,nkill,nwound";

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private DatasetVersionActivationService datasetVersionActivationService;
    @Autowired
    private DatasetVersionRepository datasetVersionRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;

    @MockBean
    private FactIncidentRepository factIncidentRepository;

    private FormLoginClient client;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        // The real staging/normalization pipeline writes fact_incident via raw batched JDBC
        // (FactIncidentBulkWriter), never through this JPA repository (CLAUDE.md section 12,
        // Phase 3 decisions) - so mocking it here does not interfere with the import itself,
        // only with DatasetVersionActivationService's own single read of it.
        doReturn(0L).when(factIncidentRepository).count();

        client = new FormLoginClient("http://localhost:" + port);
        String username = "import-crash-it-user-" + UUID.randomUUID();
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
    void crashPartwayThroughActivationLeavesNoPartialOrCorruptActiveVersion() throws Exception {
        // --- Establish a known baseline: a first import, activated normally. ---
        long baselineBatchId = uploadAndStageOnly(generateCsv("BASE", 1));
        confirmSuccessfully(baselineBatchId);
        DatasetVersion baselineActive = datasetVersionRepository.findByActiveTrue().orElseThrow();
        long baselineVersionId = baselineActive.getId();

        // --- A second import, staged but not yet confirmed - the "candidate" activation. ---
        long candidateBatchId = uploadAndStageOnly(generateCsv("CAND", 1));
        DatasetVersion candidateVersion = datasetVersionRepository.findByImportBatch_Id(candidateBatchId).orElseThrow();
        assertThat(candidateVersion.isActive()).isFalse();

        // --- Simulate a crash: the real, Spring-managed, @Transactional activate() call
        // throws on its last repository read, after having already (within the same,
        // not-yet-committed transaction) deactivated the baseline version. ---
        doThrow(new RuntimeException("simulated crash mid-activation")).when(factIncidentRepository).count();

        assertThatThrownBy(() -> datasetVersionActivationService.activate(candidateBatchId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated crash mid-activation");

        // --- Exactly one active dataset_version, still the ORIGINAL one - not zero, not two,
        // and not the half-activated candidate. ---
        var allVersions = datasetVersionRepository.findAll();
        long activeCount = allVersions.stream().filter(DatasetVersion::isActive).count();
        assertThat(activeCount)
                .withFailMessage("Expected exactly 1 active dataset_version after a crashed activation, found %d", activeCount)
                .isEqualTo(1);

        DatasetVersion stillActive = datasetVersionRepository.findByActiveTrue().orElseThrow();
        assertThat(stillActive.getId())
                .withFailMessage("The pre-crash active dataset_version must still be active after a rolled-back activation")
                .isEqualTo(baselineVersionId);

        DatasetVersion candidateAfterCrash = datasetVersionRepository.findById(candidateVersion.getId()).orElseThrow();
        assertThat(candidateAfterCrash.isActive())
                .withFailMessage("The candidate dataset_version must NOT be active after a crashed activation")
                .isFalse();
        assertThat(candidateAfterCrash.getActivatedAt()).isNull();

        // --- The batch itself must still be retryable, not incorrectly marked COMPLETED. ---
        ImportBatch candidateBatch = importBatchRepository.findById(candidateBatchId).orElseThrow();
        assertThat(candidateBatch.getStatus())
                .withFailMessage("A crashed activation must not leave the import_batch COMPLETED")
                .isEqualTo(ImportStatus.AWAITING_CONFIRMATION);

        // --- Recovery: a retried activation (crash condition cleared) must now succeed cleanly. ---
        // doReturn(...), not when(...).thenReturn(...): count() is currently stubbed to throw, and
        // when(mock.method()) evaluates that call as part of setting up the new stub - it would
        // immediately re-trigger the still-active throw before the new stub could be attached.
        doReturn(0L).when(factIncidentRepository).count();
        DatasetVersion retried = datasetVersionActivationService.activate(candidateBatchId);
        assertThat(retried.isActive()).isTrue();
        assertThat(datasetVersionRepository.findAll().stream().filter(DatasetVersion::isActive)).hasSize(1);
        assertThat(datasetVersionRepository.findByActiveTrue().orElseThrow().getId()).isEqualTo(candidateVersion.getId());
    }

    private long uploadAndStageOnly(String csvContent) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "crash-test.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
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
        return batchId;
    }

    private void confirmSuccessfully(long batchId) throws Exception {
        HttpResponse<String> confirmResponse = client.postJson("/api/import/confirm", cookie, csrfToken,
                "{\"importBatchId\":" + batchId + "}");
        assertThat(confirmResponse.statusCode()).isEqualTo(200);
    }

    private static String generateCsv(String prefix, int rowCount) {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (int i = 0; i < rowCount; i++) {
            sb.append(prefix).append(String.format("%08d", i))
                    .append(",2020,1,1,United States,North America,Bombing/Explosion,Explosives,Business,Taliban,1,1\n");
        }
        return sb.toString();
    }
}

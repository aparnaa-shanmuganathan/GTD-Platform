package com.gtd.platform.service.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.DimCountry;
import com.gtd.platform.shared.db.DimCountryRepository;
import com.gtd.platform.shared.db.DimRegionRepository;
import com.gtd.platform.shared.db.FactIncident;
import com.gtd.platform.shared.db.FactIncidentRepository;
import com.gtd.platform.shared.db.MaterializedViewStatusRepository;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 * End-to-end coverage of the IMPORT pipeline (CLAUDE.md section 8,
 * AWS-IMPORT-001) against a small synthetic CSV: upload -&gt; poll -&gt;
 * confirm, dimension auto-creation, staging-level exclusion of
 * unresolvable country/region, rejection logging, dataset_version
 * activation, materialized view refresh - then a second APPEND-mode import
 * proving an existing eventid is left untouched (and the duplicate logged),
 * and a third REPLACE-mode import proving an existing eventid IS
 * overwritten. The real-file (~180k row, &le;5 min) performance test lives
 * separately in {@link RealFileImportPerformanceTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportPipelineIntegrationTest extends PostgresIntegrationTestBase {

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
    @Autowired
    private FactIncidentRepository factIncidentRepository;
    @Autowired
    private RejectedRowRepository rejectedRowRepository;
    @Autowired
    private DatasetVersionRepository datasetVersionRepository;
    @Autowired
    private MaterializedViewStatusRepository materializedViewStatusRepository;
    @Autowired
    private DimCountryRepository dimCountryRepository;
    @Autowired
    private DimRegionRepository dimRegionRepository;

    private FormLoginClient client;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = new FormLoginClient("http://localhost:" + port);
        String username = "import-it-user-" + UUID.randomUUID();
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
    void endToEndImportLifecycleAcrossAppendAndReplaceModes() throws Exception {
        // dataset_version.row_count is, by design (CLAUDE.md section 12),
        // the CURRENT TOTAL across the whole fact_incident table at
        // activation time - not "rows this import touched". Other test
        // classes share this database with no rollback, so the baseline
        // must be captured here rather than assumed to be zero.
        long factIncidentCountBeforeThisTest = factIncidentRepository.count();

        String firstCsv = CSV_HEADER + "\n"
                + row("T00000001", "2020", "1", "15", "United States", "North America", "California", "Los Angeles",
                      "34.05", "-118.25", "Summary A", "City Hall", "Bombing/Explosion", "Explosives",
                      "Government (General)", "Taliban", "2", "3", "1", "0", "0", "1", "Motive A") + "\n"
                + row("T00000002", "2020", "2", "20", "Canada", "North America", "Ontario", "Toronto",
                      "43.65", "-79.38", "Summary B", "Bank", "Armed Assault", "Firearms", "Business", "Unknown",
                      "0", "1", "1", "0", "0", "0", "Motive B") + "\n"
                + row("T00000003", "2021", "0", "0", "Freedonia", "North America", "", "",
                      "", "", "Summary C", "", "Unarmed Assault", "Melee", "Police", "New Group X",
                      "", "", "0", "0", "0", "0", "") + "\n"
                + row("", "2020", "1", "1", "United States", "North America", "", "",
                      "", "", "", "", "", "", "", "", "", "", "", "", "", "", "") + "\n"
                + row("T00000005", "BADYEAR", "1", "1", "United States", "North America", "", "",
                      "", "", "", "", "", "", "", "", "", "", "", "", "", "", "") + "\n"
                + row("T00000006", "2020", "1", "1", "United States", "North America", "", "",
                      "", "", "", "", "", "", "", "", "-5", "0", "", "", "", "", "") + "\n"
                + row("T00000007", "2020", "1", "1", "", "", "", "",
                      "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");

        long firstBatchId = uploadStageAndConfirm(firstCsv, "APPEND");

        // --- Verify the first import's outcome ---
        assertThat(factIncidentRepository.findById("T00000001")).isPresent();
        assertThat(factIncidentRepository.findById("T00000002")).isPresent();
        FactIncident freedonia = factIncidentRepository.findById("T00000003").orElseThrow();
        DimCountry freedoniaCountry = dimCountryRepository.findByName("Freedonia").orElseThrow();
        long northAmericaRegionId = dimRegionRepository.findByName("North America").orElseThrow().getId();
        // .getId() on a lazy @ManyToOne proxy never triggers a DB round trip
        // (Hibernate populates it from the FK column alone), so these
        // comparisons are safe even though these entities came from
        // separate, already-closed repository-call transactions -
        // .getName() on the same lazy proxy would throw LazyInitializationException here.
        assertThat(freedonia.getCountry().getId()).isEqualTo(freedoniaCountry.getId());
        assertThat(freedoniaCountry.getRegion().getId()).isEqualTo(northAmericaRegionId);
        assertThat(freedonia.getEventMonth()).isNull();
        assertThat(freedonia.getEventDay()).isNull();

        List<RejectedRow> rejections = rejectedRowRepository.findByImportBatchId(firstBatchId);
        assertThat(rejections).hasSize(4);
        assertThat(rejections.stream().map(RejectedRow::getRejectionReason))
                .containsExactlyInAnyOrder(
                        RejectionReason.MISSING_OR_INVALID_EVENTID.name(),
                        RejectionReason.MISSING_OR_INVALID_YEAR.name(),
                        RejectionReason.INVALID_CASUALTY_COUNT.name(),
                        RejectionReason.UNRESOLVABLE_COUNTRY_OR_REGION.name());

        DatasetVersion firstActive = datasetVersionRepository.findByActiveTrue().orElseThrow();
        assertThat(firstActive.getRowCount()).isEqualTo((int) (factIncidentCountBeforeThisTest + 3));

        assertThat(materializedViewStatusRepository.findAll())
                .allSatisfy(mv -> assertThat(mv.getLastRefreshStatus().name()).isEqualTo("SUCCESS"));

        FactIncident originalUnitedStates = factIncidentRepository.findById("T00000001").orElseThrow();
        assertThat(originalUnitedStates.getNkill()).isEqualTo(2);

        // --- Second import, APPEND mode: existing eventid must be left untouched ---
        String secondCsv = CSV_HEADER + "\n"
                + row("T00000001", "2020", "1", "15", "United States", "North America", "California", "Los Angeles",
                      "34.05", "-118.25", "CHANGED", "City Hall", "Bombing/Explosion", "Explosives",
                      "Government (General)", "Taliban", "999", "999", "1", "0", "0", "1", "CHANGED") + "\n"
                + row("T00000008", "2020", "3", "1", "United States", "North America", "", "",
                      "", "", "Summary H", "", "Bombing/Explosion", "Explosives", "Business", "Taliban",
                      "1", "1", "1", "0", "0", "0", "Motive H");

        long secondBatchId = uploadStageAndConfirm(secondCsv, "APPEND");

        FactIncident afterAppend = factIncidentRepository.findById("T00000001").orElseThrow();
        assertThat(afterAppend.getNkill()).isEqualTo(2); // untouched, NOT 999
        assertThat(afterAppend.getSummary()).isEqualTo("Summary A");
        assertThat(factIncidentRepository.findById("T00000008")).isPresent();

        List<RejectedRow> secondRejections = rejectedRowRepository.findByImportBatchId(secondBatchId);
        assertThat(secondRejections).hasSize(1);
        assertThat(secondRejections.get(0).getRejectionReason())
                .isEqualTo(RejectionReason.DUPLICATE_EVENTID_APPEND_SKIPPED.name());
        assertThat(secondRejections.get(0).getEventId()).isEqualTo("T00000001");

        // --- Third import, REPLACE mode: existing eventid MUST be overwritten ---
        String thirdCsv = CSV_HEADER + "\n"
                + row("T00000001", "2020", "1", "15", "United States", "North America", "California", "Los Angeles",
                      "34.05", "-118.25", "REPLACED", "City Hall", "Bombing/Explosion", "Explosives",
                      "Government (General)", "Taliban", "999", "888", "1", "0", "0", "1", "REPLACED") + "\n"
                + row("T00000009", "2020", "4", "1", "United States", "North America", "", "",
                      "", "", "Summary I", "", "Bombing/Explosion", "Explosives", "Business", "Taliban",
                      "1", "1", "1", "0", "0", "0", "Motive I");

        uploadStageAndConfirm(thirdCsv, "REPLACE");

        FactIncident afterReplace = factIncidentRepository.findById("T00000001").orElseThrow();
        assertThat(afterReplace.getNkill()).isEqualTo(999);
        assertThat(afterReplace.getNwound()).isEqualTo(888);
        assertThat(afterReplace.getSummary()).isEqualTo("REPLACED");
        assertThat(factIncidentRepository.findById("T00000009")).isPresent();

        // Exactly one active dataset_version throughout - never zero, never two.
        assertThat(datasetVersionRepository.findAll().stream().filter(DatasetVersion::isActive)).hasSize(1);
    }

    private long uploadStageAndConfirm(String csvContent, String mode) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "test-import.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
                Map.of("mode", mode));
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        JsonNode uploadBody = JSON.readTree(uploadResponse.body());
        assertThat(uploadBody.path("success").asBoolean()).isTrue();
        long batchId = uploadBody.path("data").path("importBatchId").asLong();

        String terminalStatus = pollUntilTerminal(batchId, Duration.ofSeconds(30));
        assertThat(terminalStatus).isEqualTo("AWAITING_CONFIRMATION");

        HttpResponse<String> confirmResponse = client.postJson("/api/import/confirm", cookie, csrfToken,
                "{\"importBatchId\":" + batchId + "}");
        assertThat(confirmResponse.statusCode()).isEqualTo(200);
        JsonNode confirmBody = JSON.readTree(confirmResponse.body());
        assertThat(confirmBody.path("success").asBoolean()).isTrue();
        assertThat(confirmBody.path("data").path("status").asText()).isEqualTo("COMPLETED");

        return batchId;
    }

    private String pollUntilTerminal(long batchId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = client.postJson("/api/import/status", cookie, csrfToken,
                    "{\"importBatchId\":" + batchId + "}");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = JSON.readTree(response.body());
            String status = body.path("data").path("status").asText();
            if (status.equals("AWAITING_CONFIRMATION") || status.equals("FAILED")) {
                if (status.equals("FAILED")) {
                    throw new AssertionError("Import batch " + batchId + " failed: "
                            + body.path("data").path("errorMessage").asText());
                }
                return status;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Import batch " + batchId + " did not reach a terminal state within " + timeout);
    }

    private static String row(String... fields) {
        return String.join(",", java.util.Arrays.stream(fields).map(ImportPipelineIntegrationTest::csvEscape).toList());
    }

    private static String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

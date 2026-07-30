package com.gtd.platform.service.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.db.DimAttackType;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimCountry;
import com.gtd.platform.shared.db.DimCountryRepository;
import com.gtd.platform.shared.db.DimOrganization;
import com.gtd.platform.shared.db.DimOrganizationRepository;
import com.gtd.platform.shared.db.DimTargetType;
import com.gtd.platform.shared.db.DimTargetTypeRepository;
import com.gtd.platform.shared.db.DimWeaponType;
import com.gtd.platform.shared.db.DimWeaponTypeRepository;
import com.gtd.platform.shared.db.FactIncident;
import com.gtd.platform.shared.db.FactIncidentRepository;
import com.gtd.platform.shared.db.ImportBatch;
import com.gtd.platform.shared.db.ImportBatch.ImportMode;
import com.gtd.platform.shared.db.ImportBatch.ImportStatus;
import com.gtd.platform.shared.db.ImportBatchRepository;
import com.gtd.platform.shared.reporting.GeneratedReport.Format;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the REPORT service's PDF and CSV output actually contains the
 * right data - extracted via a real PDF text-stripper (PDFBox, test-only
 * dependency) and a real CSV parser (commons-csv), not just "bytes came
 * back" - against hand-computed values from known {@code fact_incident}
 * rows, the same standard {@code KpiAggregationServiceTest}/{@code
 * CompareServiceTest} hold themselves to.
 *
 * <p>Each test seeds its own distinct year (rather than sharing one fixture
 * year via {@code @BeforeEach}): that runs before every method with no
 * rollback between them, so rows inserted for a shared year would silently
 * accumulate across methods within this run - the same pitfall
 * {@code CompareServiceTest} hit and fixed in Phase 5.
 *
 * <p>Ranking/breakdown data (unlike the KPIs) comes from the
 * {@code mv_*_year_agg} materialized views (CLAUDE.md section 12, Phase 4
 * decisions), which are only refreshed by the real IMPORT pipeline - a
 * direct {@code fact_incident} insert via {@link FactIncidentRepository}
 * (the same shortcut {@code KpiAggregationServiceTest}/{@code CompareServiceTest}
 * take) leaves them stale. Each test explicitly {@code REFRESH MATERIALIZED
 * VIEW CONCURRENTLY}s the two views this report actually renders from,
 * mirroring {@code SchemaIntegrationTest}'s own pattern for the same reason.
 */
@SpringBootTest
class ReportGenerationServiceTest extends PostgresIntegrationTestBase {

    @Autowired
    private ReportGenerationService reportGenerationService;
    @Autowired
    private FactIncidentRepository factIncidentRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private DatasetVersionRepository datasetVersionRepository;
    @Autowired
    private DimCountryRepository countryRepository;
    @Autowired
    private DimAttackTypeRepository attackTypeRepository;
    @Autowired
    private DimWeaponTypeRepository weaponTypeRepository;
    @Autowired
    private DimTargetTypeRepository targetTypeRepository;
    @Autowired
    private DimOrganizationRepository organizationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DimCountry us;
    private DimCountry canada;
    private DimAttackType attackType;
    private DimWeaponType weaponType;
    private DimTargetType targetType;
    private DimOrganization org;
    private DatasetVersion datasetVersion;

    @BeforeEach
    void setUp() {
        ensureActiveDatasetVersion();
        datasetVersion = datasetVersionRepository.findByActiveTrue().orElseThrow();
        us = countryRepository.findByName("United States").orElseThrow();
        canada = countryRepository.findByName("Canada").orElseThrow();
        attackType = attackTypeRepository.findByName("Bombing/Explosion").orElseThrow();
        weaponType = weaponTypeRepository.findByName("Explosives").orElseThrow();
        targetType = targetTypeRepository.findByName("Business").orElseThrow();
        org = organizationRepository.findByName("Taliban").orElseThrow();
    }

    @Test
    void generatedPdfContainsTheRealComputedData() throws Exception {
        int year = 2071;
        seedAndRefreshViews(year);
        AnalyticalFilterRequest filter = yearFilter(year);

        ReportSummary summary = reportGenerationService.generate(filter, Format.PDF, "PDF Content Test", null);
        assertThat(summary.status()).isEqualTo("GENERATED");

        byte[] pdfBytes = reportGenerationService.download(summary.id()).content();
        assertThat(pdfBytes).startsWith('%', 'P', 'D', 'F');

        String text;
        try (var document = Loader.loadPDF(pdfBytes)) {
            text = new PDFTextStripper().getText(document);
        }

        assertThat(text).contains("PDF Content Test");
        assertThat(text).contains("Total incidents");
        assertThat(text).contains("United States");
        assertThat(text).contains("Canada");
        assertThat(text).contains("Bombing/Explosion");
        assertThat(text).contains("Explosives");
        assertThat(text).contains("Business");
        // GTD attribution footer (CLAUDE.md section 13, item 1) must appear in every generated report too.
        assertThat(text).contains("National Consortium for the Study of Terrorism and Responses to Terrorism");
    }

    @Test
    void generatedCsvContainsTheRealComputedData() throws Exception {
        int year = 2072;
        seedAndRefreshViews(year);
        AnalyticalFilterRequest filter = yearFilter(year);

        ReportSummary summary = reportGenerationService.generate(filter, Format.CSV, "CSV Content Test", null);
        assertThat(summary.status()).isEqualTo("GENERATED");

        byte[] csvBytes = reportGenerationService.download(summary.id()).content();
        List<CSVRecord> records;
        try (CSVParser parser = CSVParser.parse(new String(csvBytes, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            records = parser.getRecords();
        }

        assertThat(findRecord(records, "Report").get(1)).isEqualTo("CSV Content Test");

        CSVRecord totalIncidentsRow = findRecord(records, "Total incidents");
        assertThat(totalIncidentsRow.get(2)).isEqualTo("3");

        List<String> countryNames = records.stream()
                .filter(r -> r.size() > 1 && (r.get(1).equals("United States") || r.get(1).equals("Canada")))
                .map(r -> r.get(1))
                .toList();
        assertThat(countryNames).containsExactlyInAnyOrder("United States", "Canada");

        assertThat(findRecord(records, "Citation").get(1))
                .contains("National Consortium for the Study of Terrorism and Responses to Terrorism");
    }

    private AnalyticalFilterRequest yearFilter(int year) {
        AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
        filter.setStartDate(LocalDate.of(year, 1, 1));
        filter.setEndDate(LocalDate.of(year, 12, 31));
        return filter;
    }

    /**
     * US: 2 rows -> incidents=2, fatalities=3, injuries=1, success=50%.
     * Canada: 1 row -> incidents=1, fatalities=5, injuries=2, success=100%.
     * Combined: incidents=3.
     */
    private void seedAndRefreshViews(int year) {
        saveIncident(year, us, 2, 1, true);
        saveIncident(year, us, 1, 0, false);
        saveIncident(year, canada, 5, 2, true);

        for (String view : List.of("mv_country_year_agg", "mv_attacktype_year_agg",
                "mv_weapontype_year_agg", "mv_targettype_year_agg")) {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view);
        }
    }

    private CSVRecord findRecord(List<CSVRecord> records, String firstCell) {
        return records.stream()
                .filter(r -> r.size() > 0 && r.get(0).equals(firstCell))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No CSV record starting with '" + firstCell + "' found"));
    }

    private void ensureActiveDatasetVersion() {
        if (datasetVersionRepository.findByActiveTrue().isPresent()) {
            return;
        }
        ImportBatch batch = new ImportBatch();
        batch.setFileName("report-test-fixture.csv");
        batch.setFileSizeBytes(1024L);
        batch.setImportMode(ImportMode.APPEND);
        batch.setStatus(ImportStatus.COMPLETED);
        batch.setCreatedAt(Instant.now());
        ImportBatch savedBatch = importBatchRepository.saveAndFlush(batch);

        DatasetVersion version = new DatasetVersion();
        version.setVersionLabel("report-test-active-version");
        version.setImportBatch(savedBatch);
        version.setActive(true);
        version.setCreatedAt(Instant.now());
        datasetVersionRepository.saveAndFlush(version);
    }

    private void saveIncident(int year, DimCountry country, Integer nkill, Integer nwound, Boolean success) {
        FactIncident incident = new FactIncident();
        incident.setEventId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        incident.setDatasetVersion(datasetVersion);
        incident.setCountry(country);
        incident.setRegion(country.getRegion());
        incident.setAttackType(attackType);
        incident.setWeaponType(weaponType);
        incident.setTargetType(targetType);
        incident.setOrganization(org);
        incident.setEventYear(year);
        incident.setNkill(nkill);
        incident.setNwound(nwound);
        incident.setSuccess(success);
        incident.setCreatedAt(Instant.now());
        factIncidentRepository.save(incident);
    }
}

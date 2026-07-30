package com.gtd.platform.service.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.InsightsResponse;
import com.gtd.platform.shared.api.RankingResponse;
import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.DatasetVersionRepository;
import com.gtd.platform.shared.exception.ApiException;
import com.gtd.platform.shared.exception.ErrorCode;
import com.gtd.platform.shared.file.ReportFileStorage;
import com.gtd.platform.shared.logging.AuditService;
import com.gtd.platform.shared.reporting.GeneratedReport;
import com.gtd.platform.shared.reporting.GeneratedReport.Format;
import com.gtd.platform.shared.reporting.GeneratedReport.Status;
import com.gtd.platform.shared.reporting.GeneratedReportRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A fully-mocked unit test (matching the Mockito precedent already
 * established elsewhere, e.g. {@code RowNormalizerTest} mocking
 * {@code DimensionResolver}) for exactly the one branch that is impractical
 * to force deterministically against a real database: a rendering failure.
 * Verifies {@link ReportGenerationService#generate} still persists a
 * {@code FAILED} {@code generated_report} row (so the attempt stays visible
 * in the Report Workspace's history) before surfacing
 * {@link ErrorCode#ERR_REPORT_003} to the caller.
 */
class ReportGenerationServiceFailureTest {

    private DatasetVersionRepository datasetVersionRepository;
    private GeneratedReportRepository generatedReportRepository;
    private ReportContentService reportContentService;
    private ReportPdfRenderer reportPdfRenderer;
    private ReportCsvRenderer reportCsvRenderer;
    private ReportFileStorage reportFileStorage;
    private AuditService auditService;
    private ReportGenerationService reportGenerationService;

    @BeforeEach
    void setUp() {
        datasetVersionRepository = mock(DatasetVersionRepository.class);
        generatedReportRepository = mock(GeneratedReportRepository.class);
        reportContentService = mock(ReportContentService.class);
        reportPdfRenderer = mock(ReportPdfRenderer.class);
        reportCsvRenderer = mock(ReportCsvRenderer.class);
        reportFileStorage = mock(ReportFileStorage.class);
        auditService = mock(AuditService.class);

        reportGenerationService = new ReportGenerationService(datasetVersionRepository, generatedReportRepository,
                reportContentService, reportPdfRenderer, reportCsvRenderer, reportFileStorage, new ObjectMapper(),
                auditService);

        DatasetVersion active = new DatasetVersion();
        active.setId(1L);
        active.setVersionLabel("v-failure-test");
        active.setActive(true);
        when(datasetVersionRepository.findByActiveTrue()).thenReturn(Optional.of(active));

        // save() just echoes back what it was given, with an id assigned - mirrors what a real
        // JPA save/IDENTITY-generation round trip would hand back, without needing a real DB.
        when(generatedReportRepository.save(any(GeneratedReport.class))).thenAnswer(invocation -> {
            GeneratedReport report = invocation.getArgument(0);
            report.setId(42L);
            return report;
        });
    }

    @Test
    void aContentGatheringFailureIsPersistedAsFailedBeforeThrowing() {
        when(reportContentService.gather(any())).thenThrow(new RuntimeException("simulated content-gathering failure"));

        assertThatThrownBy(() -> reportGenerationService.generate(
                new AnalyticalFilterRequest(), Format.PDF, "Failing Report", 7L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.ERR_REPORT_003);

        ArgumentCaptor<GeneratedReport> captor = ArgumentCaptor.forClass(GeneratedReport.class);
        verify(generatedReportRepository).save(captor.capture());
        GeneratedReport persisted = captor.getValue();
        assertThat(persisted.getStatus()).isEqualTo(Status.FAILED);
        assertThat(persisted.getFilePath()).isNull();
        assertThat(persisted.getReportName()).isEqualTo("Failing Report");
        assertThat(persisted.getFormat()).isEqualTo(Format.PDF);
    }

    @Test
    void aPdfRenderingFailureIsPersistedAsFailedBeforeThrowing() {
        when(reportContentService.gather(any())).thenReturn(
                new ReportContent(List.of(),
                        new RankingResponse("countryRanking", "Top countries", List.of()),
                        new ChartResponse("trendChart", "Incidents by year", ChartResponse.ChartType.LINE, List.of(), List.of()),
                        new ChartResponse("attackChart", "Attack type", ChartResponse.ChartType.PIE, List.of(), List.of()),
                        new ChartResponse("weaponChart", "Weapon type", ChartResponse.ChartType.PIE, List.of(), List.of()),
                        new ChartResponse("targetChart", "Target type", ChartResponse.ChartType.PIE, List.of(), List.of()),
                        new InsightsResponse("execInsights", List.of())));
        when(reportPdfRenderer.render(any(), any(), any(), any())).thenThrow(new ReportRenderingException("boom", new RuntimeException()));

        assertThatThrownBy(() -> reportGenerationService.generate(
                new AnalyticalFilterRequest(), Format.PDF, "Failing Report 2", null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.ERR_REPORT_003);

        ArgumentCaptor<GeneratedReport> captor = ArgumentCaptor.forClass(GeneratedReport.class);
        verify(generatedReportRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Status.FAILED);
    }

    @Test
    void noActiveDatasetVersionThrowsErrReport002WithoutPersistingAnything() {
        when(datasetVersionRepository.findByActiveTrue()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportGenerationService.generate(
                new AnalyticalFilterRequest(), Format.PDF, "Never Generated", null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.ERR_REPORT_002);

        verify(generatedReportRepository, never()).save(any());
    }
}

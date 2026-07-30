package com.gtd.platform.service.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * REPORT (CLAUDE.md sections 4, 8, 9): orchestrates on-demand PDF/CSV report
 * generation - resolve the active {@code dataset_version} (there is no
 * per-report snapshot of {@code fact_incident} itself, since Phase 3's
 * row-level upsert semantics mean it is never historically versioned; this
 * only records which version was active at generation time), gather the
 * analytical content under {@code REPEATABLE READ} isolation via
 * {@link ReportContentService}, render it (PDF via {@link ReportPdfRenderer},
 * CSV via {@link ReportCsvRenderer}), persist the bytes via
 * {@link ReportFileStorage}, and save the immutable {@code generated_report}
 * row - {@code GENERATED} on success, {@code FAILED} (still persisted, so a
 * failed attempt stays visible in the Report Workspace's history) if
 * rendering or storage throws.
 */
@Service
public class ReportGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerationService.class);

    private final DatasetVersionRepository datasetVersionRepository;
    private final GeneratedReportRepository generatedReportRepository;
    private final ReportContentService reportContentService;
    private final ReportPdfRenderer reportPdfRenderer;
    private final ReportCsvRenderer reportCsvRenderer;
    private final ReportFileStorage reportFileStorage;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public ReportGenerationService(DatasetVersionRepository datasetVersionRepository,
            GeneratedReportRepository generatedReportRepository, ReportContentService reportContentService,
            ReportPdfRenderer reportPdfRenderer, ReportCsvRenderer reportCsvRenderer,
            ReportFileStorage reportFileStorage, ObjectMapper objectMapper, AuditService auditService) {
        this.datasetVersionRepository = datasetVersionRepository;
        this.generatedReportRepository = generatedReportRepository;
        this.reportContentService = reportContentService;
        this.reportPdfRenderer = reportPdfRenderer;
        this.reportCsvRenderer = reportCsvRenderer;
        this.reportFileStorage = reportFileStorage;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    /**
     * @param filter        the canonical filter (CAC-UI-003) active in whichever workspace triggered generation
     * @param format        PDF or CSV
     * @param reportName    a caller-supplied or auto-generated display name
     * @param adminUserId   the requesting admin, or {@code null} if unavailable
     * @return a summary of the persisted, immutable {@code generated_report} row
     * @throws ApiException {@link ErrorCode#ERR_REPORT_002} if no dataset version is active yet;
     *                       {@link ErrorCode#ERR_REPORT_003} if rendering/storage fails (a {@code FAILED}
     *                       row is still persisted first, so the attempt remains visible in history)
     */
    public ReportSummary generate(AnalyticalFilterRequest filter, Format format, String reportName, Long adminUserId) {
        AnalyticalFilterRequest effectiveFilter = (filter != null) ? filter : new AnalyticalFilterRequest();
        DatasetVersion activeDatasetVersion = datasetVersionRepository.findByActiveTrue()
                .orElseThrow(() -> new ApiException(ErrorCode.ERR_REPORT_002));

        GeneratedReport report = new GeneratedReport();
        report.setReportName(reportName);
        report.setFormat(format);
        report.setDatasetVersion(activeDatasetVersion);
        report.setFilterSnapshot(toJson(effectiveFilter));
        report.setGeneratedByAdminUserId(adminUserId);
        report.setGeneratedAt(Instant.now());

        try {
            ReportContent content = reportContentService.gather(effectiveFilter);
            String filterSummary = summarize(effectiveFilter);
            byte[] bytes = (format == Format.PDF)
                    ? reportPdfRenderer.render(reportName, activeDatasetVersion.getVersionLabel(), filterSummary, content)
                    : reportCsvRenderer.render(reportName, activeDatasetVersion.getVersionLabel(), filterSummary, content);
            String filePath = reportFileStorage.store(bytes, format == Format.PDF ? "pdf" : "csv");
            report.setFilePath(filePath);
            report.setStatus(Status.GENERATED);
        } catch (RuntimeException e) {
            log.error("Report generation failed for '{}' ({})", reportName, format, e);
            report.setStatus(Status.FAILED);
        }

        GeneratedReport saved = generatedReportRepository.save(report);
        auditService.record("REPORT_GENERATED", adminUserId, "generated_report", String.valueOf(saved.getId()),
                Map.of("format", format.name(), "status", saved.getStatus().name()), null);

        if (saved.getStatus() == Status.FAILED) {
            throw new ApiException(ErrorCode.ERR_REPORT_003);
        }
        return ReportSummary.from(saved);
    }

    public List<ReportSummary> history() {
        return generatedReportRepository.findAllByOrderByGeneratedAtDesc().stream()
                .map(ReportSummary::from)
                .toList();
    }

    public DownloadableReport download(long reportId) {
        GeneratedReport report = generatedReportRepository.findById(reportId)
                .orElseThrow(() -> new ApiException(ErrorCode.ERR_REPORT_004));
        if (report.getStatus() != Status.GENERATED || report.getFilePath() == null) {
            throw new ApiException(ErrorCode.ERR_REPORT_005);
        }
        byte[] content = reportFileStorage.read(report.getFilePath());
        return new DownloadableReport(report.getReportName(), report.getFormat(), content);
    }

    private String summarize(AnalyticalFilterRequest filter) {
        StringBuilder summary = new StringBuilder();
        if (filter.getStartDate() != null || filter.getEndDate() != null) {
            summary.append(filter.getStartDate() != null ? filter.getStartDate() : "earliest")
                    .append(" to ")
                    .append(filter.getEndDate() != null ? filter.getEndDate() : "latest");
        } else {
            summary.append("Full dataset");
        }
        int dimensionFiltersApplied = countIfNotEmpty(filter.getRegionIds()) + countIfNotEmpty(filter.getCountryIds())
                + countIfNotEmpty(filter.getAttackTypeIds()) + countIfNotEmpty(filter.getWeaponTypeIds())
                + countIfNotEmpty(filter.getTargetTypeIds()) + countIfNotEmpty(filter.getOrganizationIds());
        if (dimensionFiltersApplied > 0) {
            summary.append(", ").append(dimensionFiltersApplied).append(" additional filter(s) applied");
        }
        return summary.toString();
    }

    private int countIfNotEmpty(List<Long> ids) {
        return (ids != null && !ids.isEmpty()) ? 1 : 0;
    }

    private String toJson(AnalyticalFilterRequest filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize filter snapshot to JSON", e);
        }
    }

    /** @param content the raw file bytes (PDF or CSV) ready to stream back to the caller */
    public record DownloadableReport(String reportName, Format format, byte[] content) {
    }
}

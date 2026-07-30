package com.gtd.platform.workspace.report;

import com.gtd.platform.service.report.ReportGenerationService;
import com.gtd.platform.service.report.ReportGenerationService.DownloadableReport;
import com.gtd.platform.service.report.ReportSummary;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimRegionRepository;
import com.gtd.platform.shared.reporting.GeneratedReport.Format;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ADC-REPORT-001: the Report Workspace. {@code GET /workspace/report}
 * server-renders the history table against real, already-generated reports
 * (real data, never mock); the "generate new report" flow and downloads are
 * client-driven {@code POST /api/report/*} calls, matching CLAUDE.md section
 * 9's "all endpoints are POST".
 *
 * <p>{@link #download} is the one endpoint here that does not use the
 * section 9 envelope: it returns raw file bytes with a real
 * {@code Content-Type}/{@code Content-Disposition}, the same reasoning
 * {@code LoginController} documented in Phase 2 for non-JSON responses -
 * a browser-triggered download of a binary PDF/CSV cannot be wrapped in a
 * JSON envelope.
 */
@Controller
public class ReportController {

    private static final DateTimeFormatter DEFAULT_NAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReportGenerationService reportGenerationService;
    private final AdminUserRepository adminUserRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DimRegionRepository regionRepository;
    private final DimAttackTypeRepository attackTypeRepository;

    public ReportController(ReportGenerationService reportGenerationService, AdminUserRepository adminUserRepository,
            JdbcTemplate jdbcTemplate, DimRegionRepository regionRepository,
            DimAttackTypeRepository attackTypeRepository) {
        this.reportGenerationService = reportGenerationService;
        this.adminUserRepository = adminUserRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.regionRepository = regionRepository;
        this.attackTypeRepository = attackTypeRepository;
    }

    @GetMapping("/workspace/report")
    public String reportWorkspace(Model model, Principal principal) {
        YearBounds yearBounds = queryYearBounds();

        model.addAttribute("username", principal.getName());
        model.addAttribute("minYear", yearBounds.minYear());
        model.addAttribute("maxYear", yearBounds.maxYear());
        model.addAttribute("regions", regionRepository.findAll().stream()
                .filter(r -> !r.isUnknown())
                .sorted(Comparator.comparing(r -> r.getName()))
                .toList());
        model.addAttribute("attackTypes", attackTypeRepository.findAll().stream()
                .filter(a -> !a.isUnknown())
                .sorted(Comparator.comparing(a -> a.getName()))
                .toList());
        model.addAttribute("reports", reportGenerationService.history());
        return "report";
    }

    @PostMapping("/api/report/generate")
    @ResponseBody
    public ApiResponse<ReportSummary> generate(@Valid @RequestBody ReportGenerateRequest request, Principal principal) {
        Long adminUserId = currentAdminUserId(principal);
        String reportName = (request.reportName() != null && !request.reportName().isBlank())
                ? request.reportName()
                : defaultReportName(request.sourceWorkspace());

        ReportSummary summary = reportGenerationService.generate(request.filter(), request.format(), reportName, adminUserId);
        return ApiResponse.success(summary);
    }

    @PostMapping("/api/report/history")
    @ResponseBody
    public ApiResponse<List<ReportSummary>> history() {
        return ApiResponse.success(reportGenerationService.history());
    }

    @PostMapping("/api/report/download")
    public ResponseEntity<byte[]> download(@Valid @RequestBody ReportDownloadRequest request) {
        DownloadableReport downloadable = reportGenerationService.download(request.reportId());
        boolean isPdf = downloadable.format() == Format.PDF;
        String extension = isPdf ? "pdf" : "csv";
        MediaType contentType = isPdf ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("text/csv");

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitizeFileName(downloadable.reportName()) + "." + extension + "\"")
                .body(downloadable.content());
    }

    private Long currentAdminUserId(Principal principal) {
        return adminUserRepository.findByUsername(principal.getName())
                .map(AdminUser::getId)
                .orElse(null);
    }

    private String defaultReportName(String sourceWorkspace) {
        String source = (sourceWorkspace != null && !sourceWorkspace.isBlank()) ? sourceWorkspace : "Report Workspace";
        return source + " Report — " + DEFAULT_NAME_TIMESTAMP.format(Instant.now().atZone(ZoneOffset.UTC));
    }

    private String sanitizeFileName(String reportName) {
        return reportName.replaceAll("[^a-zA-Z0-9-_ ]", "_");
    }

    private YearBounds queryYearBounds() {
        return jdbcTemplate.queryForObject(
                "SELECT MIN(event_year) AS min_year, MAX(event_year) AS max_year FROM fact_incident",
                (rs, rowNum) -> {
                    int min = rs.getInt("min_year");
                    Integer minYear = rs.wasNull() ? null : min;
                    int max = rs.getInt("max_year");
                    Integer maxYear = rs.wasNull() ? null : max;
                    return new YearBounds(minYear, maxYear);
                });
    }

    private record YearBounds(Integer minYear, Integer maxYear) {
    }
}

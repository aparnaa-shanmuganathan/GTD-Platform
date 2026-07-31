package com.gtd.platform.workspace.dataquality;

import com.gtd.platform.service.imports.ImportBatchSummary;
import com.gtd.platform.service.imports.RejectedRowRepository;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.db.ImportBatch;
import com.gtd.platform.shared.db.ImportBatchRepository;
import com.gtd.platform.shared.exception.ApiException;
import com.gtd.platform.shared.exception.ErrorCode;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ADC-DQ-001: the Data Quality Center (Phase 10) - import batch review
 * surfacing the rejection detail Phase 3's IMPORT service already logs to
 * {@code import_rejected_row} (V10 migration), with rejection/duplicate
 * breakdowns. No dedicated service - this is orchestration over existing
 * repositories, not a new cross-cutting computation, the same "no
 * dedicated service" reasoning Region/Organization Workspaces already
 * established.
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - every endpoint here is read-only, so none requires authentication;
 * {@code principal} is {@code null} for an anonymous visitor.
 */
@Controller
public class DataQualityController {

    private static final int MAX_REJECTED_ROWS_PER_PAGE = 200;

    private final ImportBatchRepository importBatchRepository;
    private final RejectedRowRepository rejectedRowRepository;
    private final JdbcTemplate jdbcTemplate;

    public DataQualityController(ImportBatchRepository importBatchRepository,
            RejectedRowRepository rejectedRowRepository, JdbcTemplate jdbcTemplate) {
        this.importBatchRepository = importBatchRepository;
        this.rejectedRowRepository = rejectedRowRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/workspace/dataquality")
    public String dataQualityWorkspace(Model model, Principal principal) {
        model.addAttribute("username", principal != null ? principal.getName() : null);
        model.addAttribute("batches", batchSummaries());
        return "dataquality";
    }

    @PostMapping("/api/dataquality/batches")
    @ResponseBody
    public ApiResponse<List<ImportBatchSummary>> batches() {
        return ApiResponse.success(batchSummaries());
    }

    @PostMapping("/api/dataquality/batch-detail")
    @ResponseBody
    public ApiResponse<BatchDetailResponse> batchDetail(@Valid @RequestBody BatchDetailRequest request) {
        ImportBatch batch = importBatchRepository.findById(request.importBatchId())
                .orElseThrow(() -> new ApiException(ErrorCode.ERR_IMPORT_004));

        List<RejectionBreakdownEntry> breakdown = jdbcTemplate.query(
                "SELECT rejection_reason, COUNT(*) AS total FROM import_rejected_row "
                        + "WHERE import_batch_id = ? GROUP BY rejection_reason ORDER BY total DESC",
                (rs, rowNum) -> new RejectionBreakdownEntry(rs.getString("rejection_reason"), rs.getLong("total")),
                request.importBatchId());

        List<RejectedRowDetail> rejectedRows = rejectedRowRepository
                .findByImportBatchId(request.importBatchId(),
                        PageRequest.of(0, MAX_REJECTED_ROWS_PER_PAGE, Sort.by(Sort.Direction.DESC, "rejectedAt")))
                .map(RejectedRowDetail::from)
                .getContent();

        long totalRejectedRows = rejectedRowRepository.countByImportBatchId(request.importBatchId());

        BatchDetailResponse body = new BatchDetailResponse(
                ImportBatchSummary.from(batch), breakdown, rejectedRows, totalRejectedRows);
        return ApiResponse.success(body);
    }

    private List<ImportBatchSummary> batchSummaries() {
        return importBatchRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(ImportBatchSummary::from)
                .toList();
    }
}

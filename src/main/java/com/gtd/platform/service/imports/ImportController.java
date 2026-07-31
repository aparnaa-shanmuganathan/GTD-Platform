package com.gtd.platform.service.imports;

import com.gtd.platform.service.config.PlatformConfigService;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.shared.db.DatasetVersion;
import com.gtd.platform.shared.db.ImportBatch;
import com.gtd.platform.shared.db.ImportBatch.ImportMode;
import com.gtd.platform.shared.db.ImportBatchRepository;
import com.gtd.platform.shared.exception.ApiException;
import com.gtd.platform.shared.exception.ErrorCode;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The IMPORT service's HTTP surface (CLAUDE.md section 9: every endpoint is
 * POST, standard envelope). Every endpoint here sits behind normal
 * authentication like the rest of the platform (see {@code SecurityConfig}) -
 * AWS-IMPORT-001's "session-independent" property is achieved by the actual
 * staging/normalization work running on {@link ImportJobRunner}'s background
 * thread, decoupled from any one HTTP request/session's lifetime, not by
 * removing authentication from these endpoints (see CLAUDE.md section 12).
 * Only {@link #confirm} is explicitly called out by AWS-IMPORT-001 as
 * requiring a session - it does, the same as everything else here.
 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportOrchestrationService importOrchestrationService;
    private final ImportConfirmationService importConfirmationService;
    private final ImportBatchRepository importBatchRepository;
    private final AdminUserRepository adminUserRepository;
    private final PlatformConfigService platformConfigService;

    public ImportController(ImportOrchestrationService importOrchestrationService,
            ImportConfirmationService importConfirmationService, ImportBatchRepository importBatchRepository,
            AdminUserRepository adminUserRepository, PlatformConfigService platformConfigService) {
        this.importOrchestrationService = importOrchestrationService;
        this.importConfirmationService = importConfirmationService;
        this.importBatchRepository = importBatchRepository;
        this.adminUserRepository = adminUserRepository;
        this.platformConfigService = platformConfigService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImportUploadResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", required = false) ImportMode mode,
            Authentication authentication) throws IOException {

        // CONFIG's default-import-mode (Phase 10) - a real server-side fallback, not just a UI
        // pre-fill, so any caller (including a script bypassing the Administration Workspace's
        // own upload form) gets the admin-configured default when it omits mode entirely.
        ImportMode effectiveMode = mode != null ? mode : platformConfigService.getCurrent().defaultImportMode();

        if (file.isEmpty()) {
            throw new ApiException(ErrorCode.ERR_IMPORT_001, "Uploaded file is empty");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ApiException(ErrorCode.ERR_IMPORT_001, "Uploaded file must have a .csv extension");
        }

        // Copied to a stable path before returning: the servlet container's
        // own multipart temp file is not guaranteed to survive past this
        // request, but the background job (AWS-IMPORT-001) reads it long
        // after this method returns.
        Path stagedPath = Files.createTempFile("gtd-import-", ".csv");
        file.transferTo(stagedPath);

        Long adminUserId = adminUserRepository.findByUsername(authentication.getName())
                .map(AdminUser::getId)
                .orElse(null);

        ImportBatch batch = importOrchestrationService.initiateImport(
                originalFilename, file.getSize(), effectiveMode, adminUserId, stagedPath);

        ImportUploadResponse body = new ImportUploadResponse(batch.getId(), batch.getStatus().name());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(body));
    }

    @PostMapping("/status")
    public ResponseEntity<ApiResponse<ImportStatusResponse>> status(@Valid @RequestBody ImportStatusRequest request) {
        ImportBatch batch = importBatchRepository.findById(request.importBatchId())
                .orElseThrow(() -> new ApiException(ErrorCode.ERR_IMPORT_004));

        ImportStatusResponse body = new ImportStatusResponse(
                batch.getId(),
                batch.getStatus().name(),
                batch.getImportMode().name(),
                batch.getTotalRows(),
                batch.getRowsStaged(),
                batch.getRowsRejected(),
                batch.getStartedAt(),
                batch.getCompletedAt(),
                batch.getErrorMessage()
        );
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<ImportConfirmResponse>> confirm(@Valid @RequestBody ImportConfirmRequest request) {
        DatasetVersion activated = importConfirmationService.confirm(request.importBatchId());
        ImportConfirmResponse body = new ImportConfirmResponse(
                request.importBatchId(), "COMPLETED", activated.getId(), activated.getVersionLabel());
        return ResponseEntity.ok(ApiResponse.success(body));
    }
}

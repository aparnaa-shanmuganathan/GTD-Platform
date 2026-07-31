package com.gtd.platform.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * The platform's Error Catalog (CLAUDE.md section 13, open item 3): every
 * error a caller can receive is one of these numbered {@code ERR-XXX-000}
 * codes, never ad hoc prose.
 *
 * <p>Phase 1 drafted the {@code ERR-<CATEGORY>-<NNN>} structure plus the
 * IMPORT/VALIDATION/DATA entries anticipated by the schema (advisory-lock
 * conflicts, casualty-count/date parsing failures, the region/country
 * consistency trigger). Phase 2 wires this catalog into a
 * {@code @RestControllerAdvice} ({@link com.gtd.platform.shared.exception.GlobalExceptionHandler})
 * and the standard response envelope ({@link com.gtd.platform.shared.api.ApiResponse},
 * CLAUDE.md section 9), and adds the AUTH/REQUEST categories that flow needed.
 * New codes are appended here as later phases need them - never renumber or
 * repurpose an existing code once it has shipped, since callers (and tests)
 * key off the string value.
 */
public enum ErrorCode {

    // --- IMPORT: file upload / staging lifecycle (AWS-IMPORT-001) ---
    ERR_IMPORT_001("ERR-IMPORT-001", Category.IMPORT,
            "Uploaded file is not a valid CSV", HttpStatus.BAD_REQUEST),
    ERR_IMPORT_002("ERR-IMPORT-002", Category.IMPORT,
            "Uploaded file exceeds the maximum allowed size", HttpStatus.PAYLOAD_TOO_LARGE),
    ERR_IMPORT_003("ERR-IMPORT-003", Category.IMPORT,
            "An import is already in progress (advisory lock held)", HttpStatus.CONFLICT),
    ERR_IMPORT_004("ERR-IMPORT-004", Category.IMPORT,
            "Import batch not found", HttpStatus.NOT_FOUND),
    ERR_IMPORT_005("ERR-IMPORT-005", Category.IMPORT,
            "Import batch is not in a state that allows this action", HttpStatus.CONFLICT),

    // --- VALIDATION: per-row staging validation/cleansing ---
    ERR_VALIDATION_001("ERR-VALIDATION-001", Category.VALIDATION,
            "Row failed required-field validation", HttpStatus.UNPROCESSABLE_ENTITY),
    ERR_VALIDATION_002("ERR-VALIDATION-002", Category.VALIDATION,
            "Row contains a non-numeric casualty count (nkill/nwound)", HttpStatus.UNPROCESSABLE_ENTITY),
    ERR_VALIDATION_003("ERR-VALIDATION-003", Category.VALIDATION,
            "Row contains an unparseable incident date", HttpStatus.UNPROCESSABLE_ENTITY),
    ERR_VALIDATION_004("ERR-VALIDATION-004", Category.VALIDATION,
            "Duplicate eventid detected within the same batch", HttpStatus.CONFLICT),

    // --- DATA: schema/constraint-level violations surfaced to callers ---
    ERR_DATA_001("ERR-DATA-001", Category.DATA,
            "Requested dataset_version is not the currently active version", HttpStatus.CONFLICT),
    ERR_DATA_002("ERR-DATA-002", Category.DATA,
            "fact_incident.region_id is inconsistent with dim_country.region_id", HttpStatus.INTERNAL_SERVER_ERROR),

    // --- AUTH: session-based authentication (Phase 2) ---
    ERR_AUTH_001("ERR-AUTH-001", Category.AUTH,
            "Authentication is required to access this resource", HttpStatus.UNAUTHORIZED),
    ERR_AUTH_002("ERR-AUTH-002", Category.AUTH,
            "Access is denied", HttpStatus.FORBIDDEN),
    ERR_AUTH_003("ERR-AUTH-003", Category.AUTH,
            "Invalid username or password", HttpStatus.UNAUTHORIZED),

    // --- REQUEST: malformed/invalid API request bodies (Phase 2), distinct
    // from VALIDATION above which is specifically about CSV row validation ---
    ERR_REQUEST_001("ERR-REQUEST-001", Category.REQUEST,
            "Request body failed validation", HttpStatus.BAD_REQUEST),
    ERR_REQUEST_002("ERR-REQUEST-002", Category.REQUEST,
            "Request body is malformed or unreadable", HttpStatus.BAD_REQUEST),

    // --- REPORT: report generation ---
    // ERR_REPORT_001 was the Phase 4 "not yet implemented" stub for the Executive Workspace's
    // report button; kept defined (never renumbered/repurposed, per this class's own Javadoc)
    // even though no code path throws it anymore as of Phase 6's real implementation below.
    ERR_REPORT_001("ERR-REPORT-001", Category.REPORT,
            "Report generation is not yet implemented", HttpStatus.NOT_IMPLEMENTED),
    ERR_REPORT_002("ERR-REPORT-002", Category.REPORT,
            "No active dataset version - import and confirm data before generating a report", HttpStatus.CONFLICT),
    ERR_REPORT_003("ERR-REPORT-003", Category.REPORT,
            "Report generation failed", HttpStatus.INTERNAL_SERVER_ERROR),
    ERR_REPORT_004("ERR-REPORT-004", Category.REPORT,
            "Report not found", HttpStatus.NOT_FOUND),
    ERR_REPORT_005("ERR-REPORT-005", Category.REPORT,
            "This report has no downloadable file (a failed or incomplete generation)", HttpStatus.CONFLICT),

    // --- COMPARE: SVC-COMPARE country comparison / drill-down (Phase 5) ---
    ERR_COMPARE_001("ERR-COMPARE-001", Category.COMPARE,
            "Country comparison requires between 2 and 4 countryIds", HttpStatus.BAD_REQUEST),
    ERR_COMPARE_002("ERR-COMPARE-002", Category.COMPARE,
            "Country drill-down requires exactly 1 countryId", HttpStatus.BAD_REQUEST),
    ERR_COMPARE_003("ERR-COMPARE-003", Category.COMPARE,
            "Requested country id does not exist", HttpStatus.NOT_FOUND),

    // --- REGION: Region Workspace drill-down (Phase 8) ---
    ERR_REGION_001("ERR-REGION-001", Category.REGION,
            "Region drill-down requires exactly 1 regionId", HttpStatus.BAD_REQUEST),
    ERR_REGION_002("ERR-REGION-002", Category.REGION,
            "Requested region id does not exist", HttpStatus.NOT_FOUND),

    // --- ORGANIZATION: Organization Workspace drill-down (Phase 8) ---
    ERR_ORGANIZATION_001("ERR-ORGANIZATION-001", Category.ORGANIZATION,
            "Organization drill-down requires exactly 1 organizationId", HttpStatus.BAD_REQUEST),
    ERR_ORGANIZATION_002("ERR-ORGANIZATION-002", Category.ORGANIZATION,
            "Requested organization id does not exist", HttpStatus.NOT_FOUND),

    // --- CONFIG: consolidated platform configuration (Phase 10) ---
    ERR_CONFIG_001("ERR-CONFIG-001", Category.CONFIG,
            "Invalid configuration value", HttpStatus.BAD_REQUEST),

    // --- SYSTEM: catch-all ---
    ERR_SYSTEM_001("ERR-SYSTEM-001", Category.SYSTEM,
            "Unexpected internal error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final Category category;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(String code, Category category, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.category = category;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public Category category() {
        return category;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /** The {@code XXX} segment of {@code ERR-XXX-000}. */
    public enum Category {
        IMPORT,
        VALIDATION,
        DATA,
        AUTH,
        REQUEST,
        REPORT,
        COMPARE,
        REGION,
        ORGANIZATION,
        CONFIG,
        SYSTEM
    }
}

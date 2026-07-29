package com.gtd.platform.service.imports;

/**
 * Stable rejection reason codes stored in {@code import_rejected_row.rejection_reason}.
 * Treat these the same way as {@link com.gtd.platform.shared.exception.ErrorCode}:
 * never renumber or repurpose one once shipped, since the future Data
 * Quality Center will query/aggregate by this value.
 */
public enum RejectionReason {

    /** {@code eventid} is blank or not a plausible GTD event id. */
    MISSING_OR_INVALID_EVENTID,

    /** {@code iyear} is blank or not a parseable positive year. */
    MISSING_OR_INVALID_YEAR,

    /** {@code nkill}/{@code nwound} is present but not a parseable non-negative integer. */
    INVALID_CASUALTY_COUNT,

    /**
     * {@code country_txt}/{@code region_txt} could not be resolved to a
     * known or creatable dimension pairing - AWS-UNK-001's staging-level
     * exclusion for Country/Region (CLAUDE.md section 8), as opposed to the
     * is_unknown-row fallback used for Organization/Attack Type/Weapon
     * Type/Target Type.
     */
    UNRESOLVABLE_COUNTRY_OR_REGION,

    /**
     * The row's {@code eventid} already exists in {@code fact_incident} and
     * the import is running in APPEND mode, which never touches existing
     * rows - the incoming row is skipped rather than merged.
     */
    DUPLICATE_EVENTID_APPEND_SKIPPED
}

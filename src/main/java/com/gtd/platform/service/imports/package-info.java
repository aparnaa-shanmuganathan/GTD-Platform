/**
 * IMPORT cross-cutting service — CSV staging, validation, normalization, and dataset versioning (AWS-IMPORT-001).
 *
 * <p>{@code staging_incident} deliberately has no JPA entity/repository
 * (unlike every other table from the Phase 1 schema, which lives in
 * {@link com.gtd.platform.shared.db}). It is ~180k rows of raw, all-TEXT
 * data per import, loaded and read back essentially once each; a JPA entity
 * mapping 130+ columns would be brittle against Phase 3's real CSV-header
 * reconciliation and, more importantly, row-by-row entity persistence cannot
 * hit the &le;5 minute / 155MB import NFR (CLAUDE.md section 10). Phase 3's
 * IMPORT service implementation should access {@code staging_incident} via
 * direct JDBC (e.g. {@code JdbcTemplate}/{@code COPY}) instead, and this
 * package is where that access belongs.
 */
package com.gtd.platform.service.imports;

/**
 * Shared Infrastructure — database access conventions shared across workspaces and services.
 *
 * <p>Also home to the core relational data model's JPA entities/repositories
 * (dimension tables, {@code fact_incident}, {@code dataset_version},
 * {@code import_batch}, {@code materialized_view_status}) - every table from
 * CLAUDE.md section 7 except {@code staging_incident} (see
 * {@link com.gtd.platform.service.imports}), {@code admin_user}
 * ({@link com.gtd.platform.shared.auth}), {@code audit_log}
 * ({@link com.gtd.platform.shared.logging}), and {@code generated_report}
 * ({@link com.gtd.platform.shared.reporting}). These entities are shared
 * across nearly every workspace and service, so they live here rather than
 * under any single workspace/service package - and {@code import_batch}
 * specifically lives here rather than under {@code service.imports} so that
 * {@code shared.db} never depends on a service package (the IMPORT service
 * will depend on these entities, not the other way around).
 */
package com.gtd.platform.shared.db;

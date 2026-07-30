/**
 * Shared Infrastructure — file management support.
 *
 * <p>{@link com.gtd.platform.shared.file.ReportFileStorage} (Phase 6) is the
 * first real component here: permanent on-disk storage for the REPORT
 * service's generated PDF/CSV files, distinct from the IMPORT service's own
 * short-lived staged-upload handling (which stays inline in
 * {@code ImportController} - a temp file deleted once the background job
 * finishes, not a shared concern).
 */
package com.gtd.platform.shared.file;

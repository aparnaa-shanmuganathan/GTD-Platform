package com.gtd.platform.service.report;

/**
 * Wraps a checked rendering failure (PDF layout/write, CSV I/O) as an
 * unchecked exception - {@link ReportGenerationService} catches this
 * specifically to persist a {@code FAILED} {@code generated_report} row
 * rather than letting the request fail with no trace of the attempt.
 */
public class ReportRenderingException extends RuntimeException {

    public ReportRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}

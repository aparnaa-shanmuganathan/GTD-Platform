package com.gtd.platform.shared.api;

/**
 * The {@code error} object inside {@link ApiResponse} (CLAUDE.md section 9).
 * Deliberately holds a plain {@code code}/{@code message} pair rather than
 * referencing {@code com.gtd.platform.shared.exception.ErrorCode} directly -
 * {@code shared.api} does not depend on {@code shared.exception} (see this
 * package's package-info); {@code GlobalExceptionHandler} is responsible for
 * translating an {@code ErrorCode} into one of these.
 *
 * @param code    the Error Catalog code, e.g. {@code "ERR-AUTH-003"}
 * @param message a human-readable description of what went wrong
 */
public record ApiError(String code, String message) {
}

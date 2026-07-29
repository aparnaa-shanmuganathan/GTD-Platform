package com.gtd.platform.shared.exception;

/**
 * The one exception type application code should throw when it wants to
 * fail a request with a specific {@link ErrorCode}. {@link GlobalExceptionHandler}
 * maps it straight to that code's HTTP status and message in the standard
 * envelope. Prefer this over throwing a raw/generic exception whenever the
 * failure corresponds to a known Error Catalog entry.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

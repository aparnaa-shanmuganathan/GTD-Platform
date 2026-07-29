package com.gtd.platform.shared.exception;

import com.gtd.platform.shared.api.ApiError;
import com.gtd.platform.shared.api.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception a {@code @RestController} can throw to the standard
 * {@code success}/{@code data}/{@code meta}/{@code error} envelope (CLAUDE.md
 * section 9) and an Error Catalog code ({@link ErrorCode}). Every future
 * REST endpoint relies on this instead of building its own error shape - see
 * CLAUDE.md section 14's coding standards.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} to also cover the
 * Spring-MVC-internal exceptions it already knows how to catch (malformed
 * JSON, bean validation failures, etc.) without re-registering each one by
 * hand; {@link #handleExceptionInternal} is overridden once so all of those
 * come out in this platform's envelope too, instead of Spring's default
 * {@code ProblemDetail} shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex) {
        ErrorCode errorCode = ex.errorCode();
        return respond(errorCode.httpStatus(), errorCode.code(), ex.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException ex) {
        return respond(ErrorCode.ERR_AUTH_001.httpStatus(), ErrorCode.ERR_AUTH_001.code(),
                ErrorCode.ERR_AUTH_001.defaultMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        return respond(ErrorCode.ERR_AUTH_002.httpStatus(), ErrorCode.ERR_AUTH_002.code(),
                ErrorCode.ERR_AUTH_002.defaultMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "Parameter '" + ex.getName() + "' has an invalid value: '" + ex.getValue() + "'";
        return respond(ErrorCode.ERR_REQUEST_001.httpStatus(), ErrorCode.ERR_REQUEST_001.code(), detail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        log.error("Unhandled exception reached GlobalExceptionHandler", ex);
        return respond(ErrorCode.ERR_SYSTEM_001.httpStatus(), ErrorCode.ERR_SYSTEM_001.code(),
                ErrorCode.ERR_SYSTEM_001.defaultMessage());
    }

    /**
     * Catches every exception {@link ResponseEntityExceptionHandler} already
     * handles internally (bean validation failures, malformed JSON,
     * oversized multipart uploads, etc.) and re-renders it in this
     * platform's envelope instead of Spring's default body.
     *
     * <p>{@link MaxUploadSizeExceededException} is handled here rather than
     * via its own {@code @ExceptionHandler} method deliberately:
     * {@code ResponseEntityExceptionHandler} (Spring 6.1+) already declares
     * it as one of the types {@link #handleExceptionInternal} covers, and a
     * second, equally-specific {@code @ExceptionHandler(MaxUploadSizeExceededException.class)}
     * method on this subclass fails application startup with "Ambiguous
     * @ExceptionHandler method" - Spring can't rank the two as more/less
     * specific since they target the exact same type.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        ErrorCode errorCode;
        if (ex instanceof MethodArgumentNotValidException) {
            errorCode = ErrorCode.ERR_REQUEST_001;
        } else if (ex instanceof MaxUploadSizeExceededException) {
            errorCode = ErrorCode.ERR_IMPORT_002;
        } else {
            errorCode = ErrorCode.ERR_REQUEST_002;
        }

        Map<String, Object> meta = null;
        if (ex instanceof MethodArgumentNotValidException validationEx) {
            meta = new LinkedHashMap<>();
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            for (FieldError fieldError : validationEx.getBindingResult().getFieldErrors()) {
                fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
            }
            meta.put("fieldErrors", fieldErrors);
        }

        ApiResponse<Void> envelope = ApiResponse.error(new ApiError(errorCode.code(), errorCode.defaultMessage()));
        if (meta != null) {
            envelope = new ApiResponse<>(false, null, meta, envelope.error());
        }
        return ResponseEntity.status(errorCode.httpStatus()).body(envelope);
    }

    private ResponseEntity<ApiResponse<Void>> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(new ApiError(code, message)));
    }
}

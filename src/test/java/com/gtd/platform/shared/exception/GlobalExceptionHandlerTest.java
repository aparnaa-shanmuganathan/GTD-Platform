package com.gtd.platform.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gtd.platform.shared.api.ApiResponse;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Direct unit tests of {@link GlobalExceptionHandler}'s mapping logic - no
 * Spring context, no real endpoint, per Phase 2 scope ("unit tests for the
 * exception handler's mapping logic"). Integration-level coverage of
 * login/logout and audit logging lives in
 * {@code com.gtd.platform.shared.auth} instead.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiExceptionMapsToItsErrorCodeStatusAndMessage() {
        ApiException ex = new ApiException(ErrorCode.ERR_AUTH_003, "custom message");

        ResponseEntity<ApiResponse<Void>> response = handler.handleApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().error().code()).isEqualTo("ERR-AUTH-003");
        assertThat(response.getBody().error().message()).isEqualTo("custom message");
    }

    @Test
    void apiExceptionWithoutExplicitMessageUsesErrorCodeDefault() {
        ApiException ex = new ApiException(ErrorCode.ERR_IMPORT_003);

        ResponseEntity<ApiResponse<Void>> response = handler.handleApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error().code()).isEqualTo("ERR-IMPORT-003");
        assertThat(response.getBody().error().message()).isEqualTo(ErrorCode.ERR_IMPORT_003.defaultMessage());
    }

    @Test
    void authenticationExceptionMapsToErrAuth001() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAuthenticationException(new BadCredentialsException("bad creds"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().error().code()).isEqualTo("ERR-AUTH-001");
    }

    @Test
    void accessDeniedExceptionMapsToErrAuth002() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAccessDeniedException(new AccessDeniedException("nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error().code()).isEqualTo("ERR-AUTH-002");
    }

    @Test
    void unexpectedExceptionMapsToErrSystem001AndNeverLeaksInternalDetail() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnexpectedException(new IllegalStateException("some internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("ERR-SYSTEM-001");
        assertThat(response.getBody().error().message()).doesNotContain("some internal detail");
    }

    @Test
    void methodArgumentNotValidExceptionMapsToErrRequest001WithFieldErrorsInMeta() throws NoSuchMethodException {
        Method dummyMethod = DummyTarget.class.getMethod("dummyMethod", String.class);
        MethodParameter methodParameter = new MethodParameter(dummyMethod, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "startDate", "must not be null"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                ex, null, new HttpHeaders(), HttpStatus.BAD_REQUEST, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body.success()).isFalse();
        assertThat(body.error().code()).isEqualTo("ERR-REQUEST-001");
        @SuppressWarnings("unchecked")
        Map<String, String> fieldErrors = (Map<String, String>) body.meta().get("fieldErrors");
        assertThat(fieldErrors).containsEntry("startDate", "must not be null");
    }

    @Test
    void httpMessageNotReadableExceptionMapsToErrRequest002() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("Malformed JSON", mock(HttpInputMessage.class));

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                ex, null, new HttpHeaders(), HttpStatus.BAD_REQUEST, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body.error().code()).isEqualTo("ERR-REQUEST-002");
    }

    /** Method-signature fixture for building a real {@link MethodParameter} in the test above. */
    static final class DummyTarget {
        public void dummyMethod(String value) {
        }
    }
}

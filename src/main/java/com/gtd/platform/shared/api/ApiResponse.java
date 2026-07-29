package com.gtd.platform.shared.api;

import java.util.Map;

/**
 * The standard API response envelope, frozen in CLAUDE.md section 9:
 * {@code success}/{@code data}/{@code meta}/{@code error}. Every future
 * {@code @RestController} endpoint must return this shape - never invent a
 * per-controller error/response format. {@link com.gtd.platform.shared.exception.GlobalExceptionHandler}
 * is the one place that builds the {@code error} variant; controllers build
 * the {@code success} variant directly via the factory methods here.
 *
 * <p>All four fields always serialize, even when {@code null} (e.g.
 * {@code "data":null} on an error response), so clients can rely on a
 * stable shape rather than checking for field presence.
 *
 * @param success whether the request succeeded
 * @param data    the payload on success; {@code null} on error
 * @param meta    optional response metadata (pagination, timing, etc.); may be {@code null}
 * @param error   the error detail on failure; {@code null} on success
 */
public record ApiResponse<T>(boolean success, T data, Map<String, Object> meta, ApiError error) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> success(T data, Map<String, Object> meta) {
        return new ApiResponse<>(true, data, meta, null);
    }

    public static <T> ApiResponse<T> error(ApiError error) {
        return new ApiResponse<>(false, null, null, error);
    }
}

package com.gtd.platform.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Drives the real Spring Security form-login/logout flow over plain HTTP
 * (JDK {@link HttpClient}, no extra test dependency) - GET {@code /login} to
 * pick up a session cookie and CSRF token, POST the credentials, and hand
 * back the resulting authenticated session cookie. Used by both the
 * login/audit-log integration test and the session-survives-restart test,
 * which both need this exact dance.
 *
 * <p>Redirects are never auto-followed: callers need to see the 302 and its
 * {@code Location} header directly to assert against, and following a
 * cross-request redirect would also lose the distinction between the
 * pre-auth and post-auth session cookie.
 */
public final class FormLoginClient {

    private static final Pattern CSRF_PATTERN = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String baseUrl;

    public FormLoginClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public LoginResult login(String username, String password) throws IOException, InterruptedException {
        HttpResponse<String> getResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        String sessionCookie = extractCookieHeader(getResponse)
                .orElseThrow(() -> new IllegalStateException("No session cookie returned from GET /login"));
        String csrfToken = extractCsrfToken(getResponse.body());

        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(csrfToken, StandardCharsets.UTF_8);

        HttpResponse<String> postResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Cookie", sessionCookie)
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Session-fixation protection (Spring Security default) rotates the
        // session id on successful auth, so a new Set-Cookie is expected;
        // fall back to the pre-auth cookie only if the server didn't send one
        // (e.g. a failed login, where the session is unchanged).
        String resultCookie = extractCookieHeader(postResponse).orElse(sessionCookie);

        return new LoginResult(postResponse.statusCode(),
                postResponse.headers().firstValue("Location").orElse(null),
                resultCookie);
    }

    public HttpResponse<String> getWithCookie(String path, String cookie) throws IOException, InterruptedException {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Cookie", cookie)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Grabs a valid CSRF token for the given authenticated session by rendering any page that carries one. */
    public String csrfTokenFor(String cookie) throws IOException, InterruptedException {
        return extractCsrfToken(getWithCookie("/", cookie).body());
    }

    public HttpResponse<String> postJson(String path, String cookie, String csrfToken, String jsonBody)
            throws IOException, InterruptedException {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrfToken)
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** POSTs a single file plus simple string fields as multipart/form-data. */
    public HttpResponse<String> uploadMultipart(String path, String cookie, String csrfToken,
            String fileFieldName, String fileName, byte[] fileContent, String fileContentType,
            java.util.Map<String, String> otherFields) throws IOException, InterruptedException {

        String boundary = "----gtd-test-boundary-" + System.nanoTime();
        List<byte[]> parts = new java.util.ArrayList<>();

        for (var entry : otherFields.entrySet()) {
            parts.add((
                    "--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n"
                            + entry.getValue() + "\r\n"
            ).getBytes(StandardCharsets.UTF_8));
        }

        parts.add((
                "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + fileFieldName + "\"; filename=\"" + fileName + "\"\r\n"
                        + "Content-Type: " + fileContentType + "\r\n\r\n"
        ).getBytes(StandardCharsets.UTF_8));
        parts.add(fileContent);
        parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
        parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> logout(String cookie) throws IOException, InterruptedException {
        HttpResponse<String> homeResponse = getWithCookie("/", cookie);
        String csrfToken = extractCsrfToken(homeResponse.body());

        String form = "_csrf=" + URLEncoder.encode(csrfToken, StandardCharsets.UTF_8);
        return httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/logout"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public static String extractCsrfToken(String html) {
        Matcher matcher = CSRF_PATTERN.matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("No CSRF token found in response body: " + html);
        }
        return matcher.group(1);
    }

    private static Optional<String> extractCookieHeader(HttpResponse<?> response) {
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        if (setCookies.isEmpty()) {
            return Optional.empty();
        }
        String combined = setCookies.stream()
                .map(setCookie -> setCookie.split(";", 2)[0])
                .collect(Collectors.joining("; "));
        return Optional.of(combined);
    }

    public record LoginResult(int statusCode, String redirectLocation, String sessionCookie) {
        public boolean isRedirect() {
            return statusCode == 302 || statusCode == 303;
        }
    }
}

package com.gtd.platform.service.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Phase 7 hardening pass: confirms an oversized upload is rejected safely
 * (ERR-IMPORT-002, {@code MaxUploadSizeExceededException} - CLAUDE.md
 * section 12's servlet multipart limits) rather than exhausting memory/disk
 * or crashing the server. A dedicated Spring context (distinct
 * {@code max-file-size}/{@code max-request-size} properties) rather than
 * reusing the shared 250MB-limit context: actually exceeding the real
 * production limit in a test would mean generating and uploading a
 * 250MB+ file on every run, which is slow and wasteful - the *mechanism*
 * being correct matters here, not the specific configured number.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.servlet.multipart.max-file-size=10KB",
                "spring.servlet.multipart.max-request-size=10KB"
        })
class ImportOversizedFileTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private FormLoginClient client;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = new FormLoginClient("http://localhost:" + port);
        String username = "import-oversize-it-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";

        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        adminUserRepository.save(user);

        var loginResult = client.login(username, password);
        assertThat(loginResult.isRedirect()).isTrue();
        cookie = loginResult.sessionCookie();
        csrfToken = client.csrfTokenFor(cookie);
    }

    @Test
    void uploadExceedingTheConfiguredSizeLimitIsRejectedWithErrImport002() throws Exception {
        // Comfortably over the 10KB test limit above.
        StringBuilder csv = new StringBuilder("eventid,iyear,imonth,iday,country_txt,region_txt,attacktype1_txt,"
                + "weaptype1_txt,targtype1_txt,gname,nkill,nwound\n");
        for (int i = 0; i < 2000; i++) {
            csv.append("OVER").append(String.format("%08d", i))
                    .append(",2020,1,1,United States,North America,Bombing/Explosion,Explosives,Business,Taliban,1,1\n");
        }
        byte[] oversizedContent = csv.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(oversizedContent.length).isGreaterThan(10 * 1024);

        HttpResponse<String> response = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "oversized.csv", oversizedContent, "text/csv", Map.of("mode", "APPEND"));

        assertThat(response.statusCode()).isEqualTo(413);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("error").path("code").asText()).isEqualTo("ERR-IMPORT-002");
    }
}

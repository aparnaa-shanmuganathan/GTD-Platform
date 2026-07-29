package com.gtd.platform.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.GtdPlatformApplication;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.FormLoginClient.LoginResult;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Proves that a session created against one running instance of the app is
 * still valid against a completely independent, separately-started instance
 * - the point of JDBC-backed sessions (CLAUDE.md section 2), as opposed to
 * the default in-memory session store which would lose everything on
 * restart.
 *
 * <p>"Independent instance" here means a second {@link ConfigurableApplicationContext}
 * built directly via {@link SpringApplicationBuilder} against the same
 * database, with no bean/memory sharing with the first - the only thing
 * connecting the two is the {@code spring_session}/{@code spring_session_attributes}
 * rows in Postgres. That is a stronger proof than just querying
 * {@code spring_session} for a row (which only shows the mechanism is used,
 * not that a second instance actually honors it), and is achievable in a
 * single JVM without needing to literally stop and restart a process.
 * Instance A (the JUnit/{@code @SpringBootTest}-managed context) is
 * deliberately left running rather than closed, since closing it would
 * fight Spring's test context caching for later tests in this class; proof
 * comes entirely from instance B never having shared any Java state with A.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionPersistenceAcrossRestartTest extends PostgresIntegrationTestBase {

    @LocalServerPort
    private int portA;

    @Autowired
    private Environment environment;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void sessionCreatedOnOneInstanceIsHonoredByAnIndependentlyStartedInstance() throws Exception {
        String username = "restart-it-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";

        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        adminUserRepository.save(user);

        FormLoginClient clientA = new FormLoginClient("http://localhost:" + portA);
        LoginResult loginResult = clientA.login(username, password);
        assertThat(loginResult.isRedirect()).isTrue();
        String authenticatedCookie = loginResult.sessionCookie();

        ConfigurableApplicationContext instanceB = new SpringApplicationBuilder(GtdPlatformApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "spring.datasource.url=" + environment.getProperty("spring.datasource.url"),
                        "spring.datasource.username=" + environment.getProperty("spring.datasource.username"),
                        "spring.datasource.password=" + environment.getProperty("spring.datasource.password"),
                        "spring.datasource.driver-class-name=" + environment.getProperty("spring.datasource.driver-class-name")
                )
                .run();
        try {
            int portB = Integer.parseInt(instanceB.getEnvironment().getProperty("local.server.port"));
            assertThat(portB).isNotEqualTo(portA);

            FormLoginClient clientB = new FormLoginClient("http://localhost:" + portB);
            HttpResponse<String> response = clientB.getWithCookie("/", authenticatedCookie);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(username);
        } finally {
            instanceB.close();
        }
    }
}

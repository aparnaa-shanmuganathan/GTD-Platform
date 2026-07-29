package com.gtd.platform.shared.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * First-run bootstrap: if {@code admin_user} is empty, creates one account
 * with a randomly generated password and logs it once at {@code WARN}.
 *
 * <p>Not asked for explicitly in the Phase 2 prompt, but without it there is
 * no way to log in on a fresh install - every other Phase 2 acceptance
 * criterion (login works end to end) would only be demonstrable in tests,
 * which create their own fixtures. The password is random per install
 * rather than a fixed default (e.g. "admin"/"admin") specifically to avoid
 * shipping a guessable credential; this is the same pattern tools like
 * Jenkins use for exactly this air-gapped, no-side-channel first-run
 * problem (CLAUDE.md's own "no external auth providers" / offline
 * constraint rules out anything like an emailed reset link). See CLAUDE.md
 * section 12 for the full writeup and the option to replace this with
 * something else (e.g. a proper first-run setup screen) later.
 *
 * <p>{@code @Profile("!test")} keeps this out of the test suite entirely -
 * integration tests create their own named admin fixtures and must not
 * collide with (or depend on) an incidentally-bootstrapped account.
 */
@Component
@Profile("!test")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private static final String DEFAULT_USERNAME = "admin";
    private static final int PASSWORD_ENTROPY_BYTES = 18;

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapRunner(AdminUserRepository adminUserRepository, PasswordEncoder passwordEncoder) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminUserRepository.count() > 0) {
            return;
        }

        String generatedPassword = generatePassword();

        AdminUser bootstrapUser = new AdminUser();
        bootstrapUser.setUsername(DEFAULT_USERNAME);
        bootstrapUser.setPasswordHash(passwordEncoder.encode(generatedPassword));
        bootstrapUser.setActive(true);
        bootstrapUser.setCreatedAt(Instant.now());
        adminUserRepository.save(bootstrapUser);

        log.warn("No admin_user found - bootstrapped a default account. username='{}' password='{}' "
                + "-- log in immediately; this password is generated fresh per install and will not be shown again.",
                DEFAULT_USERNAME, generatedPassword);
    }

    private String generatePassword() {
        byte[] randomBytes = new byte[PASSWORD_ENTROPY_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}

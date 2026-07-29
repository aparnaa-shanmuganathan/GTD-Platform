package com.gtd.platform.shared.auth;

import com.gtd.platform.shared.logging.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Writes {@code audit_log} rows for login attempts via
 * {@link AuditService#record}, per CLAUDE.md's Phase 2 audit-logging
 * requirement. Listens for the {@link AuthenticationSuccessEvent}/
 * {@link AbstractAuthenticationFailureEvent} that Spring Boot's
 * auto-configured {@code DefaultAuthenticationEventPublisher} already
 * publishes on every authentication attempt - no extra wiring needed beyond
 * {@code spring-boot-starter-security} being on the classpath.
 *
 * <p>Deliberately a separate concern from {@link org.springframework.security.web.authentication.AuthenticationSuccessHandler}
 * (which controls the post-login redirect in {@code SecurityConfig}): audit
 * logging is a side effect of authentication succeeding, not part of
 * deciding where to send the browser next.
 */
@Component
public class AuthenticationAuditListener {

    private final AuditService auditService;
    private final AdminUserRepository adminUserRepository;
    private final HttpServletRequest request;

    public AuthenticationAuditListener(AuditService auditService, AdminUserRepository adminUserRepository,
            HttpServletRequest request) {
        this.auditService = auditService;
        this.adminUserRepository = adminUserRepository;
        this.request = request;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        AdminUser user = adminUserRepository.findByUsername(username).orElse(null);
        Long actorId = (user == null) ? null : user.getId();

        auditService.record("LOGIN_SUCCESS", actorId, "admin_user", username, null, clientIp());

        if (user != null) {
            user.setLastLoginAt(Instant.now());
            adminUserRepository.save(user);
        }
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String username = String.valueOf(event.getAuthentication().getPrincipal());
        auditService.record("LOGIN_FAILURE", null, "admin_user", username, null, clientIp());
    }

    private String clientIp() {
        return request.getRemoteAddr();
    }
}

package com.gtd.platform.shared.auth;

import com.gtd.platform.shared.logging.AuditService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Writes an {@code audit_log} row for every successful logout, then
 * delegates the actual redirect to {@link SimpleUrlLogoutSuccessHandler}.
 * Spring Security does not publish a logout event the way it does for
 * authentication attempts (see {@link AuthenticationAuditListener}), so
 * logout auditing is wired explicitly here via {@code SecurityConfig}'s
 * {@code logoutSuccessHandler(...)} instead.
 */
@Component
public class AuditingLogoutSuccessHandler implements LogoutSuccessHandler {

    private final AuditService auditService;
    private final AdminUserRepository adminUserRepository;
    private final SimpleUrlLogoutSuccessHandler delegate;

    public AuditingLogoutSuccessHandler(AuditService auditService, AdminUserRepository adminUserRepository) {
        this.auditService = auditService;
        this.adminUserRepository = adminUserRepository;
        this.delegate = new SimpleUrlLogoutSuccessHandler();
        this.delegate.setDefaultTargetUrl("/login?logout");
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        if (authentication != null) {
            String username = authentication.getName();
            Long actorId = adminUserRepository.findByUsername(username).map(AdminUser::getId).orElse(null);
            auditService.record("LOGOUT", actorId, "admin_user", username, null, request.getRemoteAddr());
        }
        delegate.onLogoutSuccess(request, response, authentication);
    }
}

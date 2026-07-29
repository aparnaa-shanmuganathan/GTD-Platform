package com.gtd.platform.shared.auth;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the login page itself. Spring Security's {@code loginPage("/login")}
 * (see {@link SecurityConfig}) only tells the filter chain where to redirect
 * unauthenticated users - it doesn't render anything - so this GET mapping is
 * required to actually return the Thymeleaf view.
 *
 * <p>{@code GET /login} is not a data API endpoint, so it does not use the
 * {@code success}/{@code data}/{@code meta}/{@code error} envelope from
 * CLAUDE.md section 9: that convention governs the analytical JSON endpoints
 * later phases build (CAC-AN-*), not server-rendered page routes. Actual
 * credential submission ({@code POST /login}) and logout ({@code POST /logout})
 * are handled by Spring Security's filter chain, not by a controller method
 * here - both are already POST, per section 9, by virtue of being standard
 * HTML form submissions.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}

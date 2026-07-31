package com.gtd.platform.shared.auth;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Landing page, linking out to whichever workspaces exist so far
 * (Executive: Phase 4, Country: Phase 5) - this gives
 * {@code defaultSuccessUrl("/", true)} in {@link SecurityConfig} somewhere
 * real to land, so the login flow is demonstrably complete rather than
 * redirecting into a 404.
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - {@code principal} is {@code null} for an anonymous visitor, so
 * {@code index.html} renders a "sign in" prompt instead of a username/
 * sign-out form for that case.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Principal principal, Model model) {
        model.addAttribute("username", principal != null ? principal.getName() : null);
        return "index";
    }
}

package com.gtd.platform.shared.auth;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Minimal authenticated landing page. No workspace exists yet (Executive
 * Workspace is Phase 4) - this just gives {@code defaultSuccessUrl("/", true)}
 * in {@link SecurityConfig} somewhere real to land, so the login flow is
 * demonstrably complete rather than redirecting into a 404.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Principal principal, Model model) {
        model.addAttribute("username", principal.getName());
        return "index";
    }
}

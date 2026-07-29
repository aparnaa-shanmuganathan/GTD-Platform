package com.gtd.platform.shared.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

/**
 * Session-based authentication, single administrator role, no RBAC
 * (CLAUDE.md section 2). CSRF protection stays enabled (Spring Security's
 * default) - {@code templates/login.html} submits the token Spring Security
 * exposes via the {@code _csrf} request attribute, no extra library needed.
 * Session-fixation protection also stays at Spring Security's secure
 * default ({@code changeSessionId}), which is what makes the session cookie
 * value change on a successful login.
 *
 * <p>Because sessions are JDBC-backed ({@code spring-session-jdbc}, wired in
 * Phase 0/1), the {@link org.springframework.security.core.context.SecurityContext}
 * this filter chain stores in the session persists in Postgres, not just
 * server memory - which is what lets a session survive an application
 * restart (see {@code SessionPersistenceAcrossRestartTest}).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LogoutSuccessHandler logoutSuccessHandler)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/fonts/**", "/js/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(logoutSuccessHandler)
                        .permitAll());
        return http.build();
    }
}

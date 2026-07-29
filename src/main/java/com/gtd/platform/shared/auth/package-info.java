/**
 * Shared Infrastructure — session-based authentication (single administrator role).
 *
 * <p>{@link com.gtd.platform.shared.auth.SecurityConfig} owns the filter
 * chain (form login, logout, CSRF); {@link com.gtd.platform.shared.auth.AdminUserDetailsService}
 * loads {@link com.gtd.platform.shared.auth.AdminUser} rows for it.
 * {@link com.gtd.platform.shared.auth.AuthenticationAuditListener} and
 * {@link com.gtd.platform.shared.auth.AuditingLogoutSuccessHandler} write
 * {@code audit_log} rows via {@link com.gtd.platform.shared.logging.AuditService}
 * on every login/logout. {@link com.gtd.platform.shared.auth.LoginController}/
 * {@link com.gtd.platform.shared.auth.HomeController} serve the two Thymeleaf
 * pages that exist so far. {@link com.gtd.platform.shared.auth.AdminBootstrapRunner}
 * seeds a first admin account on an empty database.
 */
package com.gtd.platform.shared.auth;

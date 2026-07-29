package com.gtd.platform.shared.auth;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads {@link AdminUser} rows for Spring Security. Every account gets the
 * same {@code ROLE_ADMIN} authority - CLAUDE.md section 2 freezes V1 to a
 * single administrator role with no RBAC, so there is no per-user
 * authority/permission model to look up.
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository adminUserRepository;

    public AdminUserDetailsService(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AdminUser user = adminUserRepository.findByUsername(username)
                .filter(AdminUser::isActive)
                .orElseThrow(() -> new UsernameNotFoundException("No active admin_user with username: " + username));

        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities("ROLE_ADMIN")
                .build();
    }
}

package com.recoverysystem.security;

import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.UserRole;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AuthenticatedUser implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final String email;
    private final String passwordHash;
    private final UserRole role;
    private final List<GrantedAuthority> authorities;

    public AuthenticatedUser(User user) {
        this.userId = Objects.requireNonNull(user.getId());
        this.email = Objects.requireNonNull(user.getEmail());
        this.passwordHash = Objects.requireNonNull(user.getPasswordHash());
        this.role = Objects.requireNonNull(user.getRole());
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    public Long getUserId() {
        return userId;
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    // The current users schema deliberately has no account-status fields. These four
    // flags therefore stay true until an account-status concept is added to the schema.
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}

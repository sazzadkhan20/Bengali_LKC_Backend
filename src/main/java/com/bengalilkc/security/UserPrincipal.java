package com.bengalilkc.security;

import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.domain.enums.Role;
import java.util.Collection;
import java.util.stream.Collectors;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Getter
public class UserPrincipal implements UserDetails {

    private final String id;
    private final String username;
    private final String name;
    private final String password;
    private final Role role;
    private final boolean active;
    private final boolean suspended;
    private final boolean mustChangePassword;
    private final Collection<? extends GrantedAuthority> authorities;

    public UserPrincipal(
            String id,
            String username,
            String name,
            String password,
            Role role,
            boolean active,
            boolean suspended,
            boolean mustChangePassword) {
        this.id = id;
        this.username = username;
        this.name = name;
        this.password = password;
        this.role = role;
        this.active = active;
        this.suspended = suspended;
        this.mustChangePassword = mustChangePassword;
        this.authorities = PermissionMatrix.permissionsFor(role).stream()
                .map(p -> new SimpleGrantedAuthority("PERM_" + p.name()))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean hasPermission(Permission permission) {
        return PermissionMatrix.has(role, permission);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !suspended;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active && !suspended;
    }
}

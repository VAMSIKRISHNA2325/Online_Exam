package com.onlineexam.security;

import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record ExamPrincipal(long id, String username, String displayName, @JsonIgnore String passwordHash,
                            Role role, boolean enabled) implements UserDetails {
    public static ExamPrincipal from(User user) {
        return new ExamPrincipal(user.id(), user.username(), user.displayName(),
                user.passwordHash(), user.role(), user.enabled());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    @JsonIgnore
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "ExamPrincipal[id=" + id + ", username=" + username + ", role=" + role + "]";
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ExamPrincipal principal && id == principal.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}

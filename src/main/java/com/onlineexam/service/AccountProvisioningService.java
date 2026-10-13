package com.onlineexam.service;

import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountProvisioningService {
    private static final Pattern USERNAME = Pattern.compile("[a-z][a-z0-9._-]{2,79}");
    private static final List<DemoAccount> DEMO_ACCOUNTS = List.of(
            new DemoAccount("demo_instructor1", "Asha Instructor", Role.INSTRUCTOR),
            new DemoAccount("demo_instructor2", "Dev Instructor", Role.INSTRUCTOR),
            new DemoAccount("demo_student1", "Mira Student", Role.STUDENT),
            new DemoAccount("demo_student2", "Ravi Student", Role.STUDENT),
            new DemoAccount("demo_student3", "Tara Student", Role.STUDENT));

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AccountProvisioningService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public long provision(String rawUsername, String displayName, Role role, String password) {
        String username = validatedUsername(rawUsername);
        String name = validatedName(displayName);
        if (role == null) throw new IllegalArgumentException("Account role is required");
        validatePassword(password);
        if (users.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username is already in use");
        }
        return users.create(new User(0, username, name, passwordEncoder.encode(password),
                role, true, Instant.now(clock)));
    }

    @Transactional
    public void seedDemoAccounts(String password) {
        validatePassword(password);
        for (DemoAccount account : DEMO_ACCOUNTS) {
            Optional<User> existing = users.findByUsername(account.username());
            if (existing.isPresent()) {
                User user = existing.orElseThrow();
                if (user.role() != account.role()
                        || !passwordEncoder.matches(password, user.passwordHash())) {
                    throw new IllegalStateException("A demo account already has different credentials or role");
                }
                continue;
            }
            users.create(new User(0, account.username(), account.displayName(),
                    passwordEncoder.encode(password), account.role(), true, Instant.now(clock)));
        }
    }

    private static String validatedUsername(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Username must be 3 to 80 lowercase letters, digits, dot, dash or underscore, starting with a letter");
        }
        return value;
    }

    private static String validatedName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 120) {
            throw new IllegalArgumentException("Display name must be 1 to 120 characters");
        }
        return value;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new IllegalArgumentException("Password must be 12 to 128 characters");
        }
    }

    private record DemoAccount(String username, String displayName, Role role) {
    }
}

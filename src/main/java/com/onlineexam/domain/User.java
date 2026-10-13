package com.onlineexam.domain;

import java.time.Instant;

public record User(long id, String username, String displayName, String passwordHash,
                   Role role, boolean enabled, Instant createdAt) {
}

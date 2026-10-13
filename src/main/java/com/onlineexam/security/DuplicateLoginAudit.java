package com.onlineexam.security;

import com.onlineexam.domain.SecurityEvent;
import com.onlineexam.repository.SecurityEventRepository;
import com.onlineexam.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DuplicateLoginAudit {
    private final UserRepository users;
    private final SecurityEventRepository events;
    private final Clock clock;

    public DuplicateLoginAudit(UserRepository users, SecurityEventRepository events, Clock clock) {
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void record(String username) {
        long userId = users.findByUsername(username).orElseThrow().id();
        events.create(new SecurityEvent(0, userId, null, null, "DUPLICATE_LOGIN",
                Instant.now(clock), "Second authenticated login denied"));
    }
}

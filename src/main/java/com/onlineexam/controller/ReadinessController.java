package com.onlineexam.controller;

import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReadinessController {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReadinessController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/ready")
    public Readiness ready() {
        try {
            Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
            if (!Integer.valueOf(1).equals(value)) throw new IllegalStateException("Database probe failed");
            return new Readiness("UP", Instant.now(clock));
        } catch (DataAccessException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Database is not ready.");
        }
    }

    public record Readiness(String status, Instant serverNow) {
    }
}

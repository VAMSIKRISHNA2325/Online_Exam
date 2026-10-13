package com.onlineexam.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class ReadinessControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-29T10:15:30Z"), ZoneOffset.UTC);
    private final ReadinessController controller = new ReadinessController(jdbc, clock);

    @Test
    void reportsReadyOnlyAfterDatabaseProbeSucceeds() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        ReadinessController.Readiness response = controller.ready();

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.serverNow()).isEqualTo(Instant.parse("2026-09-29T10:15:30Z"));
    }

    @Test
    void databaseFailureBecomesSafeServiceUnavailableError() {
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("private database connection detail"));

        assertThatThrownBy(controller::ready)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.code()).isEqualTo("DATABASE_UNAVAILABLE");
                    assertThat(exception.getMessage()).isEqualTo("Database is not ready.");
                });
    }
}

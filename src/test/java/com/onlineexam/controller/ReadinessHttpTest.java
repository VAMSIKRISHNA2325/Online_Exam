package com.onlineexam.controller;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.onlineexam.config.SecurityConfig;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.DuplicateLoginAudit;
import com.onlineexam.security.LoginThrottle;

@WebMvcTest(ReadinessController.class)
@Import(SecurityConfig.class)
class ReadinessHttpTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private UserRepository users;

    @MockitoBean
    private DuplicateLoginAudit duplicateLoginAudit;

    @MockitoBean
    private LoginThrottle throttle;

    @Test
    void anonymousReadinessReturnsDatabaseBackedStatusAndServerTime() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-29T10:15:30Z"));

        mvc.perform(get("/api/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.serverNow").value("2026-09-29T10:15:30Z"));
    }

    @Test
    void databaseFailureReturnsStableErrorWithoutInternalDetail() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("private database connection detail"));

        mvc.perform(get("/api/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Database is not ready."))
                .andExpect(content().string(not(containsString("private database connection detail"))));
    }

    @Test
    void unknownApiPathIsNotPublic() throws Exception {
        mvc.perform(get("/api/private-placeholder"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}

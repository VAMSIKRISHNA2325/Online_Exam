package com.onlineexam.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.config.PasswordConfig;
import com.onlineexam.config.SecurityConfig;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.DuplicateLoginAudit;
import com.onlineexam.security.LoginThrottle;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.junit.jupiter.api.extension.ExtendWith;

@WebMvcTest(AuthenticationController.class)
@Import({SecurityConfig.class, PasswordConfig.class})
class AuthenticationHttpTest {
    private static final String PASSWORD = "HttpTestPassword-29!";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder encoder;
    @Autowired private SessionRegistry sessions;
    @MockitoBean private UserRepository users;
    @MockitoBean private DuplicateLoginAudit duplicateLoginAudit;
    @MockitoBean private LoginThrottle throttle;

    @BeforeEach
    void allowFocusedAuthenticationRequests() {
        when(throttle.begin(anyString(), anyString()))
                .thenReturn(new LoginThrottle.Attempt("test", 0));
    }

    @AfterEach
    void clearMockSessions() {
        for (Object principal : sessions.getAllPrincipals()) {
            sessions.getAllSessions(principal, true)
                    .forEach(session -> sessions.removeSessionInformation(session.getSessionId()));
        }
    }

    @Test
    void validLoginReturnsSafeIdentityAndLogoutInvalidatesTheOldSession() throws Exception {
        givenStudent();
        MvcResult csrf = mvc.perform(get("/api/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        String oldId = session.getId();
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();

        mvc.perform(post("/login").session(session).param("username", "student_one")
                        .param("password", PASSWORD).param("_csrf", token))
                .andExpect(status().isNoContent());
        assertThat(session.getId()).isNotEqualTo(oldId);

        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.username").value("student_one"))
                .andExpect(jsonPath("$.displayName").value("Mira Student"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString("{bcrypt}"))));
        mvc.perform(get("/api/instructor/private").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        MvcResult freshCsrf = mvc.perform(get("/api/csrf").session(session))
                .andExpect(status().isOk()).andReturn();
        String logoutToken = json.readTree(freshCsrf.getResponse().getContentAsString())
                .get("token").asText();
        mvc.perform(post("/logout").session(session).header("X-CSRF-TOKEN", logoutToken))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        // MockHttpSession does not publish Tomcat's session-destroyed event.
        // The container lifecycle and registry cleanup are checked in the HTTP IT.
        sessions.removeSessionInformation(session.getId());

        MvcResult afterLogout = mvc.perform(get("/api/csrf"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession nextSession = (MockHttpSession) afterLogout.getRequest().getSession(false);
        String nextToken = json.readTree(afterLogout.getResponse().getContentAsString())
                .get("token").asText();
        assertThat(nextSession.getId()).isNotEqualTo(session.getId());
        mvc.perform(get("/api/me").session(nextSession))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/login").session(nextSession).param("username", "student_one")
                        .param("password", PASSWORD).param("_csrf", nextToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void missingAndInvalidCsrfRejectLoginAndLogout() throws Exception {
        givenStudent();
        MvcResult csrf = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/login").session(session).param("username", "student_one")
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/login").session(session).param("username", "student_one")
                        .param("password", PASSWORD).param("_csrf", "wrong-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());

        mvc.perform(post("/login").session(session).param("username", "student_one")
                        .param("password", PASSWORD).param("_csrf", token))
                .andExpect(status().isNoContent());
        mvc.perform(post("/logout").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session).header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndDisabledAccountCannotAuthenticate() throws Exception {
        givenStudent();
        MvcResult csrf = mvc.perform(get("/api/csrf")).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/login").session(session).param("username", "student_one")
                        .param("password", "wrong-password").param("_csrf", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());

        when(users.findByUsername("disabled_one")).thenReturn(Optional.of(new User(43,
                "disabled_one", "Disabled", encoder.encode(PASSWORD), Role.STUDENT,
                false, Instant.parse("2026-09-29T00:00:00Z"))));
        mvc.perform(post("/login").session(session).param("username", "disabled_one")
                        .param("password", PASSWORD).param("_csrf", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void databaseFailureDuringLoginHasSafeServiceUnavailableResponse(CapturedOutput logs) throws Exception {
        when(users.findByUsername("broken_user"))
                .thenThrow(new DataAccessResourceFailureException("private connection detail"));
        MvcResult csrf = mvc.perform(get("/api/csrf")).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();

        mvc.perform(post("/login").session(session).param("username", "broken_user")
                        .param("password", PASSWORD).param("_csrf", token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("private connection detail"))));
        assertThat(logs.toString()).doesNotContain("private connection detail");
    }

    private void givenStudent() {
        when(users.findByUsername("student_one")).thenReturn(Optional.of(new User(42,
                "student_one", "Mira Student", encoder.encode(PASSWORD), Role.STUDENT,
                true, Instant.parse("2026-09-29T00:00:00Z"))));
    }
}

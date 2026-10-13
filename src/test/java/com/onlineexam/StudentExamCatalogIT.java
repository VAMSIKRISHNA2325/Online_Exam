package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.service.ExamAvailabilityPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class StudentExamCatalogIT {
    private static final String JDBC_URL = IntegrationDb.url();
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private ExamRepository exams;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private ExamAvailabilityPolicy availability;

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock catalogTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> JDBC_URL);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @Test
    @Transactional
    void studentSeesBothPublishedOwnersButNeverDraftsOrAnswerKeys() throws Exception {
        long firstInstructor = user(Role.INSTRUCTOR);
        long secondInstructor = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long first = exam(firstInstructor, "First published", ExamStatus.PUBLISHED,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
        long second = exam(secondInstructor, "Second published", ExamStatus.PUBLISHED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200));
        long draft = exam(firstInstructor, "Private draft title", ExamStatus.DRAFT,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
        questionWithSecret(first, "PRIVATE_KEY_FIRST", 5);
        questionWithSecret(second, "PRIVATE_KEY_SECOND", 7);
        questionWithSecret(draft, "PRIVATE_DRAFT_KEY", 11);

        MvcResult listed = mvc.perform(get("/api/exams").with(actor(student, Role.STUDENT)))
                .andExpect(status().isOk())
                .andReturn();
        String body = listed.getResponse().getContentAsString();
        Set<Long> ids = new HashSet<>();
        for (JsonNode item : json.readTree(body)) ids.add(item.get("id").asLong());
        assertThat(ids).contains(first, second).doesNotContain(draft);
        assertThat(body).doesNotContain("PRIVATE_KEY_FIRST", "PRIVATE_KEY_SECOND",
                "PRIVATE_DRAFT_KEY", "Private draft title", "isCorrect", "correct");

        mvc.perform(get("/api/exams/{id}", first).with(actor(student, Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("First published"))
                .andExpect(jsonPath("$.availability").value("OPEN"))
                .andExpect(jsonPath("$.questionCount").value(1))
                .andExpect(jsonPath("$.maximumMarks").value(5));
        mvc.perform(get("/api/exams/{id}", second).with(actor(student, Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("UPCOMING"));
        mvc.perform(get("/api/exams/{id}", draft).with(actor(student, Role.STUDENT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/exams").with(actor(firstInstructor, Role.INSTRUCTOR)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/exams")).andExpect(status().isUnauthorized());
        // Safe GET needs no CSRF token; a state-changing request still does.
        mvc.perform(post("/api/exams/{id}/publish", draft).with(actor(firstInstructor, Role.INSTRUCTOR)))
                .andExpect(status().isForbidden());
        assertThat(exams.findById(draft)).get().extracting(Exam::status).isEqualTo(ExamStatus.DRAFT);
    }

    @Test
    @Transactional
    void exactOpenAndCloseInstantsControlCatalogAndNewAttemptGate() throws Exception {
        long instructor = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long upcomingId = exam(instructor, "Upcoming", ExamStatus.PUBLISHED,
                NOW.plusSeconds(1), NOW.plusSeconds(3600));
        long openingId = exam(instructor, "Opening now", ExamStatus.PUBLISHED,
                NOW, NOW.plusSeconds(3600));
        long closingId = exam(instructor, "Closing now", ExamStatus.PUBLISHED,
                NOW.minusSeconds(3600), NOW);
        questionWithSecret(upcomingId, "UPCOMING_KEY", 1);
        questionWithSecret(openingId, "OPENING_KEY", 1);
        questionWithSecret(closingId, "CLOSING_KEY", 1);

        mvc.perform(get("/api/exams/{id}", upcomingId).with(actor(student, Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("UPCOMING"));
        mvc.perform(get("/api/exams/{id}", openingId).with(actor(student, Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("OPEN"));
        mvc.perform(get("/api/exams/{id}", closingId).with(actor(student, Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("CLOSED"));

        Exam upcoming = exams.findById(upcomingId).orElseThrow();
        Exam opening = exams.findById(openingId).orElseThrow();
        Exam closing = exams.findById(closingId).orElseThrow();
        assertGate(upcoming, NOW, "EXAM_NOT_OPEN");
        assertThatCode(() -> availability.requireOpenForNewAttempt(upcoming, upcoming.opensAt()))
                .doesNotThrowAnyException();
        assertThatCode(() -> availability.requireOpenForNewAttempt(opening, NOW))
                .doesNotThrowAnyException();
        assertGate(closing, NOW, "EXAM_CLOSED");
        assertGate(opening, opening.closesAt(), "EXAM_CLOSED");
    }

    @Test
    @Transactional
    void draftCannotPassTheNewAttemptGate() {
        long instructor = user(Role.INSTRUCTOR);
        long draftId = exam(instructor, "Draft", ExamStatus.DRAFT,
                NOW.minusSeconds(1), NOW.plusSeconds(3600));
        assertGate(exams.findById(draftId).orElseThrow(), NOW, "NOT_FOUND");
    }

    private void assertGate(Exam exam, Instant instant, String code) {
        // The endpoint that creates attempts will call this after checking for an existing attempt.
        assertThatThrownBy(() -> availability.requireOpenForNewAttempt(exam, instant))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.status()).isEqualTo(
                            "NOT_FOUND".equals(code) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT);
                });
    }

    private long questionWithSecret(long examId, String keyText, int marks) {
        long id = questions.create(new Question(0, examId, "Safe question title", marks, 0));
        options.create(new QuestionOption(0, id, keyText, true, 0));
        options.create(new QuestionOption(0, id, "Distractor", false, 1));
        return id;
    }

    private long exam(long owner, String title, ExamStatus status, Instant opens, Instant closes) {
        return exams.create(new Exam(0, owner, title, "Catalog description", 30,
                opens, closes, status, status == ExamStatus.PUBLISHED ? NOW.minusSeconds(300) : null));
    }

    private long user(Role role) {
        String username = "catalog_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Catalog fixture", "{bcrypt}unused",
                role, true, NOW));
    }

    private static RequestPostProcessor actor(long id, Role role) {
        ExamPrincipal principal = new ExamPrincipal(id, "catalog_fixture_" + id,
                "Catalog fixture", "{bcrypt}unused", role, true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}

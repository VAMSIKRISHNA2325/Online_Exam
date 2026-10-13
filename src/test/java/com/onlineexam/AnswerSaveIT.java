package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onlineexam.domain.Answer;
import com.onlineexam.domain.AnswerRevision;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.dto.AnswerSaveRequest;
import com.onlineexam.dto.StudentAnswerView;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.service.AnswerRevisionConflict;
import com.onlineexam.service.AnswerService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class AnswerSaveIT {
    private static final String JDBC_URL = IntegrationDb.url();
    private static final Instant BASE = Instant.parse("2026-09-29T10:00:00Z");
    private static final AtomicReference<Instant> TEST_TIME = new AtomicReference<>(BASE);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ExamRepository exams;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private AttemptRepository attempts;
    @Autowired private AnswerRepository answers;
    @Autowired private AnswerService service;
    @Autowired private PlatformTransactionManager transactionManager;

    @TestConfiguration
    static class ControllableTime {
        @Bean
        @Primary
        Clock answerTestClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return TEST_TIME.get(); }
            };
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> JDBC_URL);
        properties.add("spring.datasource.username", IntegrationDb::username);
        properties.add("spring.datasource.password", IntegrationDb::password);
    }

    @BeforeEach
    void resetClock() {
        TEST_TIME.set(BASE);
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Transactional
    void saveClearAndRetryPersistOneRevisionPerChange() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(600));
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.firstOption(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedOptionId").value(fixture.firstOption()))
                .andExpect(jsonPath("$.revision").value(1));
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.firstOption(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedOptionId").isEmpty())
                .andExpect(jsonPath("$.revision").value(2));
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
        Answer stored = answers.find(fixture.attempt(), fixture.question()).orElseThrow();
        assertThat(stored.selectedOptionId()).isNull();
        assertThat(stored.revision()).isEqualTo(2);
        assertThat(answers.findRevisions(fixture.attempt(), fixture.question()))
                .extracting(AnswerRevision::revision).containsExactly(1, 2);
        mvc.perform(get("/api/attempts/{id}", fixture.attempt())
                        .with(actor(fixture.student(), Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].revision").value(2))
                .andExpect(jsonPath("$.questions[0].selectedOptionId").isEmpty());
    }

    @Test
    @Transactional
    void staleForeignAndMalformedRequestsCannotChangeAnswer() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(600));
        long otherQuestion = questions.create(new Question(0, fixture.exam(), "Other question", 2, 1));
        long foreignOption = options.create(new QuestionOption(0, otherQuestion, "Other", true, 0));
        options.create(new QuestionOption(0, otherQuestion, "No", false, 1));
        long otherExam = exams.create(new Exam(0, fixture.teacher(), "Other exam", "", 10,
                BASE.minusSeconds(60), BASE.plusSeconds(600), ExamStatus.PUBLISHED,
                BASE.minusSeconds(120)));
        long foreignQuestion = questions.create(new Question(0, otherExam, "Foreign question", 1, 0));

        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.firstOption(), 0)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.secondOption(), 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVISION"))
                .andExpect(jsonPath("$.current.selectedOptionId").value(fixture.firstOption()))
                .andExpect(jsonPath("$.current.revision").value(1));
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(foreignOption, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OPTION"));
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), foreignQuestion)
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, 0)))
                .andExpect(status().isNotFound());
        long outsider = user(Role.STUDENT);
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(outsider, Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.secondOption(), 1)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.teacher(), Role.INSTRUCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.secondOption(), 1)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT))
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.secondOption(), 1)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedOptionId\":0,\"expectedRevision\":-1}"))
                .andExpect(status().isBadRequest());
        assertThat(answers.find(fixture.attempt(), fixture.question())).get()
                .extracting(Answer::selectedOptionId).isEqualTo(fixture.firstOption());
        assertThat(answers.findRevisions(fixture.attempt(), fixture.question())).hasSize(1);
    }

    @Test
    @Transactional
    void deadlineAndTerminalStateAreRecheckedBeforeEveryWrite() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(1));
        TEST_TIME.set(BASE.plusSeconds(1).minusNanos(1_000));
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.firstOption(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        TEST_TIME.set(BASE.plusSeconds(1));
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.secondOption(), 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_EXPIRED"));
        assertThat(answers.find(fixture.attempt(), fixture.question())).get()
                .extracting(Answer::selectedOptionId).isEqualTo(fixture.firstOption());

        attempts.finalizeAttempt(fixture.attempt(), AttemptStatus.TIMED_OUT,
                BASE.plusSeconds(2), 0, 3);
        TEST_TIME.set(BASE);
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.secondOption(), 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_FINALIZED"));
        assertThat(answers.findRevisions(fixture.attempt(), fixture.question())).hasSize(1);
    }

    @Test
    void concurrentSavesAcrossConnectionsHaveOneWinnerAndCurrentConflict() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(600));
        CountDownLatch firstSaved = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        AtomicLong secondConnection = new AtomicLong();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                actAs(fixture.student());
                try {
                    return new TransactionTemplate(transactionManager).execute(transaction -> {
                        long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        StudentAnswerView saved = service.save(fixture.attempt(), fixture.question(),
                                new AnswerSaveRequest(fixture.firstOption(), 0));
                        firstSaved.countDown();
                        await(releaseFirst);
                        return new SaveOutcome(connectionId, saved);
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(firstSaved.await(10, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> {
                actAs(fixture.student());
                try {
                    try {
                        return new TransactionTemplate(transactionManager).execute(transaction -> {
                            secondConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                            secondReady.countDown();
                            service.save(fixture.attempt(), fixture.question(),
                                    new AnswerSaveRequest(fixture.secondOption(), 0));
                            throw new AssertionError("Expected a stale revision");
                        });
                    } catch (AnswerRevisionConflict conflict) {
                        return new SaveOutcome(secondConnection.get(), conflict.current());
                    }
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(secondReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            SaveOutcome winner = first.get(10, TimeUnit.SECONDS);
            SaveOutcome loser = second.get(10, TimeUnit.SECONDS);
            assertThat(winner.connectionId()).isNotEqualTo(loser.connectionId());
            assertThat(winner.answer().revision()).isEqualTo(1);
            assertThat(loser.answer().revision()).isEqualTo(1);
            assertThat(loser.answer().selectedOptionId()).isEqualTo(fixture.firstOption());
            assertThat(answers.find(fixture.attempt(), fixture.question())).get()
                    .extracting(Answer::selectedOptionId).isEqualTo(fixture.firstOption());
            assertThat(answers.findRevisions(fixture.attempt(), fixture.question()))
                    .extracting(AnswerRevision::revision).containsExactly(1);
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM attempts WHERE id = ?", fixture.attempt());
            jdbc.update("DELETE FROM exams WHERE id = ?", fixture.exam());
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", fixture.teacher(), fixture.student());
        }
    }

    private String body(Long selected, int revision) {
        ObjectNode body = json.createObjectNode();
        if (selected == null) body.putNull("selectedOptionId");
        else body.put("selectedOptionId", selected);
        body.put("expectedRevision", revision);
        return body.toString();
    }

    private Fixture fixture(Instant deadline) {
        long teacher = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long examId = exams.create(new Exam(0, teacher, "Answer fixture", "", 30,
                BASE.minusSeconds(3600), BASE.plusSeconds(3600), ExamStatus.PUBLISHED,
                BASE.minusSeconds(7200)));
        long questionId = questions.create(new Question(0, examId, "Pick one", 3, 0));
        long firstOption = options.create(new QuestionOption(0, questionId, "First", true, 0));
        long secondOption = options.create(new QuestionOption(0, questionId, "Second", false, 1));
        long attemptId = attempts.create(new Attempt(0, examId, student, AttemptStatus.IN_PROGRESS,
                BASE.minusSeconds(60), deadline, null, null, null));
        return new Fixture(teacher, student, examId, questionId, firstOption, secondOption, attemptId);
    }

    private long user(Role role) {
        String username = "answer_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Answer fixture", "{bcrypt}unused",
                role, true, BASE.minusSeconds(7200)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", exception);
        }
    }

    private static RequestPostProcessor actor(long id, Role role) {
        ExamPrincipal principal = principal(id, role);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static void actAs(long student) {
        ExamPrincipal principal = principal(student, Role.STUDENT);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static ExamPrincipal principal(long id, Role role) {
        return new ExamPrincipal(id, "answer_fixture_" + id, "Answer fixture",
                "{bcrypt}unused", role, true);
    }

    private record Fixture(long teacher, long student, long exam, long question,
                           long firstOption, long secondOption, long attempt) {
    }

    private record SaveOutcome(long connectionId, StudentAnswerView answer) {
    }
}

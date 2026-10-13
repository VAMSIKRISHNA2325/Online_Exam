package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Answer;
import com.onlineexam.domain.AnswerRevision;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.dto.StudentAttemptView;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.service.AttemptService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class AttemptStartIT {
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
    @Autowired private AttemptService service;
    @Autowired private PlatformTransactionManager transactionManager;

    @TestConfiguration
    static class ControllableTime {
        @Bean
        @Primary
        Clock attemptTestClock() {
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
    void startPersistsServerDeadlineAndRefreshOrClosedRetryKeepsOriginalState() throws Exception {
        long teacher = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long examId = exam(teacher, ExamStatus.PUBLISHED,
                BASE.minusSeconds(60), BASE.plusSeconds(900), 30);
        long questionId = question(examId);
        long selected = options.findByQuestion(questionId).getFirst().id();

        MvcResult first = mvc.perform(post("/api/exams/{id}/attempts", examId)
                        .with(actor(student, Role.STUDENT)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.questions[0].revision").value(0))
                .andReturn();
        long attemptId = json.readTree(first.getResponse().getContentAsString()).get("id").asLong();
        Attempt stored = attempts.findById(attemptId).orElseThrow();
        assertThat(stored.startedAt()).isEqualTo(BASE);
        assertThat(stored.deadlineAt()).isEqualTo(BASE.plusSeconds(900));
        assertThat(first.getResponse().getContentAsString())
                .doesNotContain("\"correct\":", "\"isCorrect\":");

        answers.create(new Answer(attemptId, examId, questionId, selected, 1, BASE.plusSeconds(10)));
        answers.appendRevision(new AnswerRevision(attemptId, questionId, 1, selected,
                BASE.plusSeconds(10)));
        mvc.perform(get("/api/attempts/{id}", attemptId).with(actor(student, Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].selectedOptionId").value(selected))
                .andExpect(jsonPath("$.questions[0].revision").value(1));

        TEST_TIME.set(BASE.plusSeconds(900));
        mvc.perform(post("/api/exams/{id}/attempts", examId)
                        .with(actor(student, Role.STUDENT)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(attemptId))
                .andExpect(jsonPath("$.deadlineAt").value("2026-09-29T10:15:00Z"))
                .andExpect(jsonPath("$.questions[0].revision").value(1));
        assertThat(attempts.findByExamAndStudent(examId, student)).get()
                .extracting(Attempt::id).isEqualTo(attemptId);
        assertThat(attempts.findByExam(examId)).hasSize(1);
    }

    @Test
    @Transactional
    void durationLimitAndExactOpeningClosingEdgesAreServerControlled() throws Exception {
        long teacher = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long upcomingId = exam(teacher, ExamStatus.PUBLISHED,
                BASE.plusSeconds(1), BASE.plusSeconds(3600), 10);
        long closingId = exam(teacher, ExamStatus.PUBLISHED,
                BASE.minusSeconds(3600), BASE, 10);
        long draftId = exam(teacher, ExamStatus.DRAFT,
                BASE.minusSeconds(1), BASE.plusSeconds(3600), 10);
        question(upcomingId);
        question(closingId);
        question(draftId);

        mvc.perform(post("/api/exams/{id}/attempts", upcomingId)
                        .with(actor(student, Role.STUDENT)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAM_NOT_OPEN"));
        mvc.perform(post("/api/exams/{id}/attempts", closingId)
                        .with(actor(student, Role.STUDENT)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAM_CLOSED"));
        mvc.perform(post("/api/exams/{id}/attempts", draftId)
                        .with(actor(student, Role.STUDENT)).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(attempts.findByExamAndStudent(upcomingId, student)).isEmpty();
        assertThat(attempts.findByExamAndStudent(closingId, student)).isEmpty();

        TEST_TIME.set(BASE.plusSeconds(1));
        MvcResult opened = mvc.perform(post("/api/exams/{id}/attempts", upcomingId)
                        .with(actor(student, Role.STUDENT)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        long attemptId = json.readTree(opened.getResponse().getContentAsString()).get("id").asLong();
        assertThat(attempts.findById(attemptId)).get().extracting(Attempt::deadlineAt)
                .isEqualTo(BASE.plusSeconds(601));
    }

    @Test
    @Transactional
    void onlyOwningStudentCanReadAndInstructorCannotStart() throws Exception {
        long teacher = user(Role.INSTRUCTOR);
        long firstStudent = user(Role.STUDENT);
        long secondStudent = user(Role.STUDENT);
        long examId = exam(teacher, ExamStatus.PUBLISHED,
                BASE.minusSeconds(60), BASE.plusSeconds(3600), 30);
        question(examId);
        MvcResult started = mvc.perform(post("/api/exams/{id}/attempts", examId)
                        .with(actor(firstStudent, Role.STUDENT)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        long attemptId = json.readTree(started.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/attempts").with(actor(firstStudent, Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(attemptId))
                .andExpect(jsonPath("$[0].examId").value(examId));
        MvcResult otherList = mvc.perform(get("/api/attempts")
                        .with(actor(secondStudent, Role.STUDENT)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json.readTree(otherList.getResponse().getContentAsString()).size()).isZero();
        mvc.perform(get("/api/attempts").with(actor(teacher, Role.INSTRUCTOR)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/attempts/{id}", attemptId).with(actor(secondStudent, Role.STUDENT)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/attempts/{id}", attemptId).with(actor(teacher, Role.INSTRUCTOR)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/{id}/attempts", examId)
                        .with(actor(teacher, Role.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/{id}/attempts", examId).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/exams/{id}/attempts", examId)
                        .with(actor(secondStudent, Role.STUDENT)))
                .andExpect(status().isForbidden());
        assertThat(attempts.findByExam(examId)).hasSize(1);
    }

    @Test
    void concurrentDistinctConnectionsConvergeEvenWhenWindowClosesBeforeSecondLock() throws Exception {
        long teacher = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long examId = exam(teacher, ExamStatus.PUBLISHED,
                BASE.minusSeconds(60), BASE.plusSeconds(60), 30);
        question(examId);
        CountDownLatch firstCreated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                actAs(student);
                try {
                    return new TransactionTemplate(transactionManager).execute(transaction -> {
                        long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        StudentAttemptView view = service.start(examId);
                        firstCreated.countDown();
                        await(releaseFirst);
                        return new ConnectionAttempt(connectionId, view);
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(firstCreated.await(10, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> {
                actAs(student);
                try {
                    return new TransactionTemplate(transactionManager).execute(transaction -> {
                        long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        secondReady.countDown();
                        return new ConnectionAttempt(connectionId, service.start(examId));
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(secondReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            TEST_TIME.set(BASE.plusSeconds(60));
            releaseFirst.countDown();
            ConnectionAttempt firstResult = first.get(10, TimeUnit.SECONDS);
            ConnectionAttempt secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResult.connectionId()).isNotEqualTo(secondResult.connectionId());
            assertThat(secondResult.view().id()).isEqualTo(firstResult.view().id());
            assertThat(secondResult.view().deadlineAt()).isEqualTo(BASE.plusSeconds(60));
            assertThat(attempts.findByExam(examId)).hasSize(1);
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM attempts WHERE exam_id = ?", examId);
            jdbc.update("DELETE FROM exams WHERE id = ?", examId);
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", teacher, student);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", exception);
        }
    }

    private long question(long examId) {
        long id = questions.create(new Question(0, examId, "Question", 3, 0));
        options.create(new QuestionOption(0, id, "First option", true, 0));
        options.create(new QuestionOption(0, id, "Second option", false, 1));
        return id;
    }

    private long exam(long teacher, ExamStatus status, Instant opens,
                      Instant closes, int durationMinutes) {
        return exams.create(new Exam(0, teacher, "Attempt fixture", "", durationMinutes,
                opens, closes, status, status == ExamStatus.PUBLISHED ? BASE.minusSeconds(300) : null));
    }

    private long user(Role role) {
        String username = "attempt_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Attempt fixture", "{bcrypt}unused",
                role, true, BASE.minusSeconds(3600)));
    }

    private static RequestPostProcessor actor(long id, Role role) {
        ExamPrincipal principal = principal(id, role);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static void actAs(long studentId) {
        ExamPrincipal principal = principal(studentId, Role.STUDENT);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static ExamPrincipal principal(long id, Role role) {
        return new ExamPrincipal(id, "attempt_fixture_" + id, "Attempt fixture",
                "{bcrypt}unused", role, true);
    }

    private record ConnectionAttempt(long connectionId, StudentAttemptView view) {
    }
}

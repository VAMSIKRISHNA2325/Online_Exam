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
import com.onlineexam.controller.ApiException;
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
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
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
class SaveDeadlineRollbackIT {
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
    @Autowired private AnswerService answerService;
    @Autowired private PlatformTransactionManager transactionManager;

    @TestConfiguration
    static class ControllableTime {
        @Bean
        @Primary
        Clock rollbackTestClock() {
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
    void saveJustBeforeDeadlineSucceedsButExactAndLaterInstantsReject() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(1));
        TEST_TIME.set(BASE.plusSeconds(1).minusNanos(1_000));
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.firstOption(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        TEST_TIME.set(BASE.plusSeconds(1));
        rejectedAtDeadline(fixture);
        TEST_TIME.set(BASE.plusSeconds(2));
        rejectedAtDeadline(fixture);
        assertThat(answers.find(fixture.attempt(), fixture.question())).get()
                .extracting(Answer::selectedOptionId).isEqualTo(fixture.firstOption());
        assertThat(answers.findRevisions(fixture.attempt(), fixture.question()))
                .extracting(AnswerRevision::revision).containsExactly(1);
    }

    @Test
    void databaseFailureAfterAnswerUpdateRollsBackAndReturnsNoSuccess() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(600));
        Instant originalSave = BASE.minusSeconds(10);
        answers.create(new Answer(fixture.attempt(), fixture.exam(), fixture.question(),
                fixture.firstOption(), 1, originalSave));
        answers.appendRevision(new AnswerRevision(fixture.attempt(), fixture.question(),
                1, fixture.firstOption(), originalSave));
        // A reserved history revision forces a real MySQL duplicate-key failure after
        // AnswerService has updated the answer row. The update and failed append share one transaction.
        answers.appendRevision(new AnswerRevision(fixture.attempt(), fixture.question(),
                2, fixture.secondOption(), BASE.minusSeconds(5)));
        try {
            var failed = mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                            fixture.attempt(), fixture.question())
                            .with(actor(fixture.student())).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(fixture.secondOption(), 1)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andReturn();
            assertThat(failed.getResponse().getContentAsString())
                    .doesNotContain("Duplicate", "SQL", "answer_revisions");
            Answer after = answers.find(fixture.attempt(), fixture.question()).orElseThrow();
            assertThat(after.selectedOptionId()).isEqualTo(fixture.firstOption());
            assertThat(after.revision()).isEqualTo(1);
            assertThat(after.savedAt()).isEqualTo(originalSave);
            mvc.perform(get("/api/attempts/{id}", fixture.attempt())
                            .with(actor(fixture.student())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.questions[0].selectedOptionId")
                            .value(fixture.firstOption()))
                    .andExpect(jsonPath("$.questions[0].revision").value(1));
            jdbc.update("DELETE FROM answer_revisions WHERE attempt_id = ? AND question_id = ? AND revision = 2",
                    fixture.attempt(), fixture.question());
            mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                            fixture.attempt(), fixture.question())
                            .with(actor(fixture.student())).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(fixture.secondOption(), 1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revision").value(2));
            assertThat(answers.find(fixture.attempt(), fixture.question()).orElseThrow().selectedOptionId())
                    .isEqualTo(fixture.secondOption());
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void saveWaitingOnAttemptLockChecksDeadlineAfterItAcquiresTheLock() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(1));
        try {
            LockRace race = raceWithHeldLock(fixture, false);
            assertThat(race.saveCode()).isEqualTo("ATTEMPT_EXPIRED");
            assertThat(race.firstConnection()).isNotEqualTo(race.secondConnection());
            assertThat(answers.findByAttempt(fixture.attempt())).isEmpty();
            assertThat(attempts.findById(fixture.attempt())).get()
                    .extracting(Attempt::status).isEqualTo(AttemptStatus.IN_PROGRESS);
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void terminalWriteUnderSameAttemptLockBeatsWaitingSave() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(600));
        try {
            LockRace race = raceWithHeldLock(fixture, true);
            assertThat(race.saveCode()).isEqualTo("ATTEMPT_FINALIZED");
            assertThat(race.firstConnection()).isNotEqualTo(race.secondConnection());
            assertThat(answers.findByAttempt(fixture.attempt())).isEmpty();
            assertThat(attempts.findById(fixture.attempt())).get()
                    .extracting(Attempt::status).isEqualTo(AttemptStatus.SUBMITTED);
        } finally {
            cleanup(fixture);
        }
    }

    private LockRace raceWithHeldLock(Fixture fixture, boolean terminal) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch saveReady = new CountDownLatch(1);
        AtomicLong saveConnection = new AtomicLong();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var holder = workers.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                attempts.findByIdForUpdate(fixture.attempt()).orElseThrow();
                if (terminal) {
                    assertThat(attempts.finalizeAttempt(fixture.attempt(), AttemptStatus.SUBMITTED,
                            BASE.plusSeconds(1), 0, 3)).isEqualTo(1);
                }
                locked.countDown();
                await(release);
                return connectionId;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var saver = workers.submit(() -> {
                actAs(fixture.student());
                try {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                            saveConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                            saveReady.countDown();
                            answerService.save(fixture.attempt(), fixture.question(),
                                    new AnswerSaveRequest(fixture.firstOption(), 0));
                        });
                        throw new AssertionError("A waiting save must be rejected");
                    } catch (ApiException exception) {
                        return exception.code();
                    }
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(saveReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> saver.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            if (!terminal) TEST_TIME.set(fixture.deadline());
            release.countDown();
            long holderConnection = holder.get(10, TimeUnit.SECONDS);
            String saveCode = saver.get(10, TimeUnit.SECONDS);
            return new LockRace(holderConnection, saveConnection.get(), saveCode);
        } finally {
            release.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private void rejectedAtDeadline(Fixture fixture) throws Exception {
        mvc.perform(put("/api/attempts/{attemptId}/answers/{questionId}",
                        fixture.attempt(), fixture.question())
                        .with(actor(fixture.student())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.secondOption(), 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_EXPIRED"));
    }

    private String body(long selected, int expectedRevision) {
        ObjectNode body = json.createObjectNode();
        body.put("selectedOptionId", selected);
        body.put("expectedRevision", expectedRevision);
        return body.toString();
    }

    private Fixture fixture(Instant deadline) {
        long teacher = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long examId = exams.create(new Exam(0, teacher, "Rollback fixture", "", 30,
                BASE.minusSeconds(3600), BASE.plusSeconds(3600), ExamStatus.PUBLISHED,
                BASE.minusSeconds(7200)));
        long questionId = questions.create(new Question(0, examId, "Question", 3, 0));
        long firstOption = options.create(new QuestionOption(0, questionId, "First", true, 0));
        long secondOption = options.create(new QuestionOption(0, questionId, "Second", false, 1));
        long attemptId = attempts.create(new Attempt(0, examId, student,
                AttemptStatus.IN_PROGRESS, BASE.minusSeconds(60), deadline,
                null, null, null));
        return new Fixture(teacher, student, examId, questionId,
                firstOption, secondOption, attemptId, deadline);
    }

    private long user(Role role) {
        String username = "rollback_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Rollback fixture", "{bcrypt}unused",
                role, true, BASE.minusSeconds(7200)));
    }

    private void cleanup(Fixture fixture) {
        jdbc.update("DELETE FROM attempts WHERE id = ?", fixture.attempt());
        jdbc.update("DELETE FROM exams WHERE id = ?", fixture.exam());
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", fixture.teacher(), fixture.student());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", exception);
        }
    }

    private static RequestPostProcessor actor(long student) {
        ExamPrincipal principal = principal(student);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static void actAs(long student) {
        ExamPrincipal principal = principal(student);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static ExamPrincipal principal(long student) {
        return new ExamPrincipal(student, "rollback_fixture_" + student,
                "Rollback fixture", "{bcrypt}unused", Role.STUDENT, true);
    }

    private record Fixture(long teacher, long student, long exam, long question,
                           long firstOption, long secondOption, long attempt,
                           Instant deadline) {
    }

    private record LockRace(long firstConnection, long secondConnection, String saveCode) {
    }
}

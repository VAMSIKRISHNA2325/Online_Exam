package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Answer;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.dto.AnswerSaveRequest;
import com.onlineexam.dto.StudentResultView;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.service.AnswerService;
import com.onlineexam.service.FinalizationService;
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
class FinalizationIT {
    private static final String JDBC_URL = IntegrationDb.url();
    private static final Instant BASE = Instant.parse("2026-09-29T10:00:00Z");
    private static final AtomicReference<Instant> TEST_TIME = new AtomicReference<>(BASE);

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ExamRepository exams;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private AttemptRepository attempts;
    @Autowired private AnswerRepository answers;
    @Autowired private AnswerService answerService;
    @Autowired private FinalizationService finalization;
    @Autowired private PlatformTransactionManager transactionManager;

    @TestConfiguration
    static class ControllableTime {
        @Bean @Primary Clock finalizationTestClock() {
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

    @BeforeEach void resetClock() { TEST_TIME.set(BASE); }
    @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }

    @Test
    @Transactional
    void persistedGradingCoversCorrectMixedWrongUnansweredAndCleared() throws Exception {
        Fixture correct = fixture(BASE.plusSeconds(600));
        answer(correct, correct.firstQuestion(), correct.firstCorrect(), 1);
        answer(correct, correct.secondQuestion(), correct.secondCorrect(), 1);
        assertScore(correct, 5, "100.00");

        Fixture mixed = fixture(BASE.plusSeconds(600));
        answer(mixed, mixed.firstQuestion(), mixed.firstCorrect(), 1);
        answer(mixed, mixed.secondQuestion(), mixed.secondWrong(), 1);
        assertScore(mixed, 2, "40.00");

        Fixture wrong = fixture(BASE.plusSeconds(600));
        answer(wrong, wrong.firstQuestion(), wrong.firstWrong(), 1);
        answer(wrong, wrong.secondQuestion(), wrong.secondWrong(), 1);
        assertScore(wrong, 0, "0.00");

        Fixture unanswered = fixture(BASE.plusSeconds(600));
        assertScore(unanswered, 0, "0.00");

        Fixture cleared = fixture(BASE.plusSeconds(600));
        answer(cleared, cleared.firstQuestion(), cleared.firstCorrect(), 1);
        actAs(cleared.student());
        answerService.save(cleared.attempt(), cleared.firstQuestion(),
                new AnswerSaveRequest(null, 1));
        assertThat(answers.find(cleared.attempt(), cleared.firstQuestion()).orElseThrow().revision())
                .isEqualTo(2);
        assertScore(cleared, 0, "0.00");
    }

    @Test
    @Transactional
    void repeatedSubmitReturnsPersistedOutcomeAndCannotAcceptClientScore() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(600));
        answer(fixture, fixture.firstQuestion(), fixture.firstCorrect(), 1);
        mvc.perform(post("/api/attempts/{id}/submit", fixture.attempt())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf())
                        .contentType("application/json").content("{\"score\":999,\"totalMarks\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.score").value(2))
                .andExpect(jsonPath("$.totalMarks").value(5))
                .andExpect(jsonPath("$.percentage").value(40.00))
                .andExpect(jsonPath("$.questions").doesNotExist());
        Attempt first = attempts.findById(fixture.attempt()).orElseThrow();
        TEST_TIME.set(BASE.plusSeconds(700));
        mvc.perform(post("/api/attempts/{id}/submit", fixture.attempt())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.score").value(2))
                .andExpect(jsonPath("$.finalizedAt").value(first.finalizedAt().toString()));
        assertThat(attempts.findById(fixture.attempt()).orElseThrow()).isEqualTo(first);
        TEST_TIME.set(BASE);
        mvc.perform(post("/api/attempts/{id}/submit", fixture.attempt())
                        .with(actor(fixture.student(), Role.STUDENT)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/attempts/{id}/submit", fixture.attempt()).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/attempts/{id}/submit", fixture.attempt())
                        .with(actor(fixture.teacher(), Role.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isForbidden());
        long outsider = user(Role.STUDENT);
        mvc.perform(post("/api/attempts/{id}/submit", fixture.attempt())
                        .with(actor(outsider, Role.STUDENT)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    void exactDeadlineTimesOutOnceAndSchedulerUsesSameResult() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(1));
        answer(fixture, fixture.firstQuestion(), fixture.firstCorrect(), 1);
        assertThat(finalization.finalizeIfExpired(fixture.attempt())).isEmpty();
        TEST_TIME.set(BASE.plusSeconds(1));
        mvc.perform(post("/api/attempts/{id}/submit", fixture.attempt())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TIMED_OUT"))
                .andExpect(jsonPath("$.score").value(2));
        Attempt timedOut = attempts.findById(fixture.attempt()).orElseThrow();
        assertThat(finalization.finalizeIfExpired(fixture.attempt())).get()
                .extracting(StudentResultView::status).isEqualTo(AttemptStatus.TIMED_OUT);
        assertThat(attempts.findById(fixture.attempt()).orElseThrow()).isEqualTo(timedOut);
        Fixture schedulerFixture = fixture(BASE.plusSeconds(1));
        StudentResultView scheduled = finalization.finalizeIfExpired(schedulerFixture.attempt())
                .orElseThrow();
        assertThat(scheduled.status()).isEqualTo(AttemptStatus.TIMED_OUT);
        assertThat(scheduled.score()).isZero();
        assertThat(scheduled.totalMarks()).isEqualTo(5);
    }

    @Test
    void failedGradingRollsBackWithoutPublishingAResult() {
        Fixture fixture = fixture(BASE.plusSeconds(600));
        // Deliberately corrupt a published paper to exercise a mid-finalization failure.
        jdbc.update("DELETE FROM question_options WHERE question_id = ?", fixture.secondQuestion());
        actAs(fixture.student());
        try {
            assertThatThrownBy(() -> finalization.submit(fixture.attempt()))
                    .isInstanceOf(IllegalStateException.class);
            Attempt persisted = attempts.findById(fixture.attempt()).orElseThrow();
            assertThat(persisted.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
            assertThat(persisted.finalizedAt()).isNull();
            assertThat(persisted.score()).isNull();
            assertThat(persisted.totalMarks()).isNull();
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void failedMySqlFinalizationWriteReturnsNoResultAndRetrySucceeds() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(600));
        answer(fixture, fixture.firstQuestion(), fixture.firstCorrect(), 1);
        String constraint = "ck_test_finalization_write_failure";
        try {
            dropFinalizationFaultConstraint(constraint);
            jdbc.execute(("ALTER TABLE attempts ADD CONSTRAINT " + constraint
                    + " CHECK (student_id <> %d OR status = 'IN_PROGRESS')")
                    .formatted(fixture.student()));
            var failed = mvc.perform(post("/api/attempts/{id}/submit", fixture.attempt())
                            .with(actor(fixture.student(), Role.STUDENT)).with(csrf()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andReturn();
            assertThat(failed.getResponse().getContentAsString())
                    .doesNotContain("SQL", "constraint", constraint);
            Attempt afterFailure = attempts.findById(fixture.attempt()).orElseThrow();
            assertThat(afterFailure.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
            assertThat(afterFailure.finalizedAt()).isNull();
            assertThat(afterFailure.score()).isNull();
            assertThat(afterFailure.totalMarks()).isNull();
            assertThat(answers.find(fixture.attempt(), fixture.firstQuestion()).orElseThrow()
                    .selectedOptionId()).isEqualTo(fixture.firstCorrect());

            dropFinalizationFaultConstraint(constraint);
            mvc.perform(post("/api/attempts/{id}/submit", fixture.attempt())
                            .with(actor(fixture.student(), Role.STUDENT)).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.score").value(2))
                    .andExpect(jsonPath("$.totalMarks").value(5));
            Attempt recovered = attempts.findById(fixture.attempt()).orElseThrow();
            assertThat(recovered.status()).isEqualTo(AttemptStatus.SUBMITTED);
            assertThat(recovered.score()).isEqualTo(2);
            assertThat(recovered.totalMarks()).isEqualTo(5);
        } finally {
            dropFinalizationFaultConstraint(constraint);
            cleanup(fixture);
        }
    }

    private void dropFinalizationFaultConstraint(String name) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = 'attempts' "
                        + "AND constraint_name = ? AND constraint_type = 'CHECK'",
                Integer.class, name);
        if (count != null && count > 0) jdbc.execute("ALTER TABLE attempts DROP CHECK " + name);
    }

    @Test
    void submitHoldingRowLockMakesRacingSaveFailAfterCommit() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(600));
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        AtomicLong secondConnection = new AtomicLong();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                actAs(fixture.student());
                try {
                    return new TransactionTemplate(transactionManager).execute(tx -> {
                        long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        StudentResultView result = finalization.submit(fixture.attempt());
                        firstLocked.countDown();
                        await(releaseFirst);
                        return new ResultOnConnection(connection, result);
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> {
                actAs(fixture.student());
                try {
                    try {
                        new TransactionTemplate(transactionManager).execute(tx -> {
                            secondConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                            secondReady.countDown();
                            answerService.save(fixture.attempt(), fixture.firstQuestion(),
                                    new AnswerSaveRequest(fixture.firstCorrect(), 0));
                            return null;
                        });
                        throw new AssertionError("Expected finalized attempt");
                    } catch (ApiException error) {
                        assertThat(error.code()).isEqualTo("ATTEMPT_FINALIZED");
                        return secondConnection.get();
                    }
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(secondReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            ResultOnConnection result = first.get(10, TimeUnit.SECONDS);
            assertThat(result.connection()).isNotEqualTo(second.get(10, TimeUnit.SECONDS));
            assertThat(result.result().score()).isZero();
            assertThat(answers.findByAttempt(fixture.attempt())).isEmpty();
            assertThat(attempts.findById(fixture.attempt()).orElseThrow().score()).isZero();
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            cleanup(fixture);
        }
    }

    @Test
    void saveHoldingRowLockIsIncludedInRacingSubmission() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(600));
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        AtomicLong secondConnection = new AtomicLong();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                actAs(fixture.student());
                try {
                    return new TransactionTemplate(transactionManager).execute(tx -> {
                        long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        answerService.save(fixture.attempt(), fixture.firstQuestion(),
                                new AnswerSaveRequest(fixture.firstCorrect(), 0));
                        firstLocked.countDown();
                        await(releaseFirst);
                        return connection;
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> {
                actAs(fixture.student());
                try {
                    return new TransactionTemplate(transactionManager).execute(tx -> {
                        secondConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        secondReady.countDown();
                        return finalization.submit(fixture.attempt());
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(secondReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isNotEqualTo(secondConnection.get());
            StudentResultView result = second.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(AttemptStatus.SUBMITTED);
            assertThat(result.score()).isEqualTo(2);
            assertThat(result.totalMarks()).isEqualTo(5);
            assertThat(attempts.findById(fixture.attempt()).orElseThrow().score()).isEqualTo(2);
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            cleanup(fixture);
        }
    }

    @Test
    void timeoutWaitsForInFlightSaveAndGradesItsCommittedSelection() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(1));
        CountDownLatch saveLocked = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        CountDownLatch timeoutReady = new CountDownLatch(1);
        AtomicLong timeoutConnection = new AtomicLong();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var save = workers.submit(() -> {
                actAs(fixture.student());
                try {
                    return new TransactionTemplate(transactionManager).execute(tx -> {
                        long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        answerService.save(fixture.attempt(), fixture.firstQuestion(),
                                new AnswerSaveRequest(fixture.firstCorrect(), 0));
                        saveLocked.countDown();
                        await(releaseSave);
                        return connection;
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(saveLocked.await(10, TimeUnit.SECONDS)).isTrue();
            TEST_TIME.set(BASE.plusSeconds(1));
            var timeout = workers.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(tx -> {
                        timeoutConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        timeoutReady.countDown();
                        return finalization.finalizeIfExpired(fixture.attempt()).orElseThrow();
                    }));
            assertThat(timeoutReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> timeout.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseSave.countDown();
            assertThat(save.get(10, TimeUnit.SECONDS)).isNotEqualTo(timeoutConnection.get());
            StudentResultView result = timeout.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(AttemptStatus.TIMED_OUT);
            assertThat(result.score()).isEqualTo(2);
            assertThat(result.totalMarks()).isEqualTo(5);
            assertThat(attempts.findById(fixture.attempt()).orElseThrow().score()).isEqualTo(2);
        } finally {
            releaseSave.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            cleanup(fixture);
        }
    }

    private void assertScore(Fixture fixture, int expected, String percentage) throws Exception {
        mvc.perform(post("/api/attempts/{id}/submit", fixture.attempt())
                        .with(actor(fixture.student(), Role.STUDENT)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(expected))
                .andExpect(jsonPath("$.totalMarks").value(5))
                .andExpect(jsonPath("$.percentage").value(Double.parseDouble(percentage)))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        Attempt persisted = attempts.findById(fixture.attempt()).orElseThrow();
        assertThat(persisted.score()).isEqualTo(expected);
        assertThat(persisted.totalMarks()).isEqualTo(5);
        assertThat(persisted.finalizedAt()).isEqualTo(BASE);
    }

    private void answer(Fixture fixture, long questionId, Long selected, int revision) {
        answers.create(new Answer(fixture.attempt(), fixture.exam(), questionId,
                selected, revision, BASE.minusSeconds(1)));
    }

    private Fixture fixture(Instant deadline) {
        long teacher = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long exam = exams.create(new Exam(0, teacher, "Finalization fixture", "", 30,
                BASE.minusSeconds(3600), BASE.plusSeconds(3600), ExamStatus.PUBLISHED,
                BASE.minusSeconds(7200)));
        long first = questions.create(new Question(0, exam, "First", 2, 0));
        long firstCorrect = options.create(new QuestionOption(0, first, "Yes", true, 0));
        long firstWrong = options.create(new QuestionOption(0, first, "No", false, 1));
        long second = questions.create(new Question(0, exam, "Second", 3, 1));
        long secondCorrect = options.create(new QuestionOption(0, second, "Yes", true, 0));
        long secondWrong = options.create(new QuestionOption(0, second, "No", false, 1));
        long attempt = attempts.create(new Attempt(0, exam, student, AttemptStatus.IN_PROGRESS,
                BASE.minusSeconds(60), deadline, null, null, null));
        return new Fixture(teacher, student, exam, first, firstCorrect, firstWrong,
                second, secondCorrect, secondWrong, attempt);
    }

    private long user(Role role) {
        String username = "final_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Finalization fixture", "{bcrypt}unused",
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
        return new ExamPrincipal(id, "final_fixture_" + id, "Finalization fixture",
                "{bcrypt}unused", role, true);
    }

    private record Fixture(long teacher, long student, long exam,
                           long firstQuestion, long firstCorrect, long firstWrong,
                           long secondQuestion, long secondCorrect, long secondWrong,
                           long attempt) { }

    private record ResultOnConnection(long connection, StudentResultView result) { }
}

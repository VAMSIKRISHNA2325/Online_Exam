package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.onlineexam.service.AttemptService;
import com.onlineexam.service.ExpiryRecovery;
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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"exam.expiry.scan-delay-ms=3600000", "exam.expiry.batch-size=2"})
@AutoConfigureMockMvc
class ExpiryRecoveryIT {
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
    @Autowired private AttemptService attemptService;
    @Autowired private AnswerService answerService;
    @Autowired private FinalizationService finalization;
    @Autowired private ExpiryRecovery recovery;
    @Autowired private PlatformTransactionManager transactionManager;

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledTime {
        @Bean @Primary Clock expiryTestClock() {
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

    @BeforeEach void resetTime() { TEST_TIME.set(BASE); }
    @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }

    @Test
    void startupInFreshContextPreservesLiveAndFinalizesOverduePersistedAttempts() {
        Fixture fixture = fixture(BASE.plusSeconds(1));
        Fixture live = fixture(BASE.plusSeconds(600));
        saveCorrect(fixture);
        saveCorrect(live);
        Instant originalDeadline = attempts.findById(fixture.attempt()).orElseThrow().deadlineAt();
        Instant liveDeadline = attempts.findById(live.attempt()).orElseThrow().deadlineAt();
        TEST_TIME.set(BASE.plusSeconds(2));
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(
                OnlineExamApplication.class, ControlledTime.class)
                .web(WebApplicationType.SERVLET)
                .run("--spring.datasource.url=" + JDBC_URL,
                        "--spring.datasource.username=" + IntegrationDb.username(),
                        "--spring.datasource.password=" + IntegrationDb.password(),
                        "--server.port=0", "--exam.expiry.scan-delay-ms=3600000",
                        "--spring.main.banner-mode=off")) {
            Attempt recovered = restarted.getBean(AttemptRepository.class)
                    .findById(fixture.attempt()).orElseThrow();
            assertThat(recovered.status()).isEqualTo(AttemptStatus.TIMED_OUT);
            assertThat(recovered.score()).isEqualTo(2);
            assertThat(recovered.totalMarks()).isEqualTo(2);
            assertThat(recovered.deadlineAt()).isEqualTo(originalDeadline);
            assertThat(recovered.finalizedAt()).isEqualTo(BASE.plusSeconds(2));
            Attempt stillLive = restarted.getBean(AttemptRepository.class)
                    .findById(live.attempt()).orElseThrow();
            assertThat(stillLive.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
            assertThat(stillLive.deadlineAt()).isEqualTo(liveDeadline);
            assertThat(stillLive.score()).isNull();
            assertThat(restarted.getBean(AnswerRepository.class)
                    .find(live.attempt(), live.question())).get()
                    .extracting(Answer::selectedOptionId).isEqualTo(live.correct());
        } finally {
            assertThat(answers.find(fixture.attempt(), fixture.question())).get()
                    .extracting(Answer::selectedOptionId).isEqualTo(fixture.correct());
            cleanup(fixture);
            cleanup(live);
        }
    }

    @Test
    void readAndListLazilyRecoverDueAttemptsWithoutChangingDeadline() throws Exception {
        Fixture readFixture = fixture(BASE.plusSeconds(1));
        Fixture listFixture = fixture(BASE.plusSeconds(1));
        saveCorrect(readFixture);
        try {
            TEST_TIME.set(BASE.plusSeconds(1));
            mvc.perform(get("/api/attempts/{id}", readFixture.attempt())
                            .with(authentication(token(readFixture.student()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("TIMED_OUT"))
                    .andExpect(jsonPath("$.score").value(2))
                    .andExpect(jsonPath("$.deadlineAt").value(BASE.plusSeconds(1).toString()));
            actAs(listFixture.student());
            assertThat(attemptService.listMine()).singleElement()
                    .satisfies(summary -> {
                        assertThat(summary.status()).isEqualTo(AttemptStatus.TIMED_OUT);
                        assertThat(summary.deadlineAt()).isEqualTo(BASE.plusSeconds(1));
                        assertThat(summary.score()).isZero();
                    });
        } finally {
            cleanup(readFixture);
            cleanup(listFixture);
        }
    }

    @Test
    void eachSweepProcessesAtMostConfiguredBatchAndEventuallyReachesLaterIds() {
        Fixture first = fixture(BASE.plusSeconds(1));
        Fixture second = fixture(BASE.plusSeconds(1));
        Fixture third = fixture(BASE.plusSeconds(1));
        try {
            TEST_TIME.set(BASE.plusSeconds(1));
            assertThat(recovery.scanBatch()).isLessThanOrEqualTo(2);
            int terminalAfterOne = terminalCount(first, second, third);
            assertThat(terminalAfterOne).isLessThanOrEqualTo(2);
            for (int i = 0; i < 6 && terminalCount(first, second, third) < 3; i++) {
                assertThat(recovery.scanBatch()).isLessThanOrEqualTo(2);
            }
            assertThat(terminalCount(first, second, third)).isEqualTo(3);
        } finally {
            cleanup(first);
            cleanup(second);
            cleanup(third);
        }
    }

    @Test
    void submitRechecksTimeAfterWaitingForAnswerRowLock() throws Exception {
        Fixture fixture = fixture(BASE.plusSeconds(1));
        CountDownLatch saveLocked = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        CountDownLatch submitReady = new CountDownLatch(1);
        AtomicLong submitConnection = new AtomicLong();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var save = workers.submit(() -> {
                actAs(fixture.student());
                try {
                    return new TransactionTemplate(transactionManager).execute(tx -> {
                        long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        answerService.save(fixture.attempt(), fixture.question(),
                                new AnswerSaveRequest(fixture.correct(), 0));
                        saveLocked.countDown();
                        await(releaseSave);
                        return connection;
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(saveLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var submit = workers.submit(() -> {
                actAs(fixture.student());
                try {
                    return new TransactionTemplate(transactionManager).execute(tx -> {
                        submitConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        submitReady.countDown();
                        return finalization.submit(fixture.attempt());
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(submitReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> submit.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            TEST_TIME.set(BASE.plusSeconds(1));
            releaseSave.countDown();
            assertThat(save.get(10, TimeUnit.SECONDS)).isNotEqualTo(submitConnection.get());
            StudentResultView result = submit.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(AttemptStatus.TIMED_OUT);
            assertThat(result.score()).isEqualTo(2);
            assertThat(result.deadlineAt()).isEqualTo(BASE.plusSeconds(1));
        } finally {
            releaseSave.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            cleanup(fixture);
        }
    }

    private int terminalCount(Fixture... fixtures) {
        int count = 0;
        for (Fixture fixture : fixtures) {
            if (attempts.findById(fixture.attempt()).orElseThrow().status() != AttemptStatus.IN_PROGRESS) {
                count++;
            }
        }
        return count;
    }

    private Fixture fixture(Instant deadline) {
        long teacher = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long exam = exams.create(new Exam(0, teacher, "Expiry fixture", "", 30,
                BASE.minusSeconds(3600), BASE.plusSeconds(3600), ExamStatus.PUBLISHED,
                BASE.minusSeconds(7200)));
        long question = questions.create(new Question(0, exam, "Pick one", 2, 0));
        long correct = options.create(new QuestionOption(0, question, "Yes", true, 0));
        options.create(new QuestionOption(0, question, "No", false, 1));
        long attempt = attempts.create(new Attempt(0, exam, student, AttemptStatus.IN_PROGRESS,
                BASE.minusSeconds(60), deadline, null, null, null));
        return new Fixture(teacher, student, exam, question, correct, attempt);
    }

    private void saveCorrect(Fixture fixture) {
        answers.create(new Answer(fixture.attempt(), fixture.exam(), fixture.question(),
                fixture.correct(), 1, BASE));
    }

    private long user(Role role) {
        String username = "expiry_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Expiry fixture", "{bcrypt}unused",
                role, true, BASE.minusSeconds(7200)));
    }

    private void cleanup(Fixture fixture) {
        jdbc.update("DELETE FROM attempts WHERE id = ?", fixture.attempt());
        jdbc.update("DELETE FROM exams WHERE id = ?", fixture.exam());
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", fixture.teacher(), fixture.student());
    }

    private static UsernamePasswordAuthenticationToken token(long id) {
        ExamPrincipal principal = new ExamPrincipal(id, "expiry_fixture_" + id,
                "Expiry fixture", "{bcrypt}unused", Role.STUDENT, true);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static void actAs(long student) {
        SecurityContextHolder.getContext().setAuthentication(token(student));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", exception);
        }
    }

    private record Fixture(long teacher, long student, long exam, long question,
                           long correct, long attempt) { }
}

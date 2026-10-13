package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;

import com.onlineexam.domain.Answer;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "exam.expiry.scan-delay-ms=100")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClosedBrowserExpiryIT {
    private static final String JDBC_URL = IntegrationDb.url();
    private static final Instant BASE = Instant.parse("2026-09-29T10:00:00Z");
    private static final AtomicReference<Instant> TEST_TIME = new AtomicReference<>(BASE);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ExamRepository exams;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionOptionRepository options;
    @Autowired private AttemptRepository attempts;
    @Autowired private AnswerRepository answers;

    @TestConfiguration
    static class ControlledTime {
        @Bean @Primary Clock scheduledExpiryTestClock() {
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

    @Test
    void schedulerFinalizesStoredAnswersWithoutAnyClientRequest() throws Exception {
        long teacher = user(Role.INSTRUCTOR);
        long student = user(Role.STUDENT);
        long exam = exams.create(new Exam(0, teacher, "Closed client fixture", "", 30,
                BASE.minusSeconds(3600), BASE.plusSeconds(3600), ExamStatus.PUBLISHED,
                BASE.minusSeconds(7200)));
        long question = questions.create(new Question(0, exam, "Pick one", 3, 0));
        long correct = options.create(new QuestionOption(0, question, "Yes", true, 0));
        options.create(new QuestionOption(0, question, "No", false, 1));
        Instant deadline = BASE.plusSeconds(1);
        long attempt = attempts.create(new Attempt(0, exam, student, AttemptStatus.IN_PROGRESS,
                BASE.minusSeconds(60), deadline, null, null, null));
        answers.create(new Answer(attempt, exam, question, correct, 1, BASE));
        try {
            assertThat(attempts.findById(attempt).orElseThrow().status())
                    .isEqualTo(AttemptStatus.IN_PROGRESS);
            TEST_TIME.set(deadline);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Attempt stored;
            do {
                stored = attempts.findById(attempt).orElseThrow();
                if (stored.status() == AttemptStatus.TIMED_OUT) break;
                Thread.sleep(25);
            } while (System.nanoTime() < until);
            assertThat(stored.status()).isEqualTo(AttemptStatus.TIMED_OUT);
            assertThat(stored.score()).isEqualTo(3);
            assertThat(stored.totalMarks()).isEqualTo(3);
            assertThat(stored.deadlineAt()).isEqualTo(deadline);
            assertThat(stored.finalizedAt()).isEqualTo(deadline);
            assertThat(answers.find(attempt, question).orElseThrow().selectedOptionId())
                    .isEqualTo(correct);
        } finally {
            TEST_TIME.set(BASE);
            jdbc.update("DELETE FROM attempts WHERE id = ?", attempt);
            jdbc.update("DELETE FROM exams WHERE id = ?", exam);
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", teacher, student);
        }
    }

    private long user(Role role) {
        String username = "closed_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Closed client fixture", "{bcrypt}unused",
                role, true, BASE.minusSeconds(7200)));
    }
}

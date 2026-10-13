package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onlineexam.domain.Answer;
import com.onlineexam.domain.AnswerRevision;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.SecurityEvent;
import com.onlineexam.domain.User;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.SecurityEventRepository;
import com.onlineexam.repository.UserRepository;
import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real-MySQL repository contract checks. All fixture rows are rolled back. */
class RepositoryIT {
    private static final Instant CREATED = Instant.parse("2026-09-29T10:00:00.123456Z");
    private static final Instant OPENS = Instant.parse("2026-09-28T00:00:00Z");
    private static final Instant CLOSES = Instant.parse("2026-09-30T00:00:00Z");
    private static TransactionTemplate transactions;
    private static UserRepository users;
    private static ExamRepository exams;
    private static QuestionRepository questions;
    private static QuestionOptionRepository options;
    private static AttemptRepository attempts;
    private static AnswerRepository answers;
    private static SecurityEventRepository events;

    @BeforeAll
    static void connectOnlyToIntegrationDatabase() throws Exception {
        String url = System.getenv().getOrDefault("MYSQL_IT_DB_URL",
                "jdbc:mysql://127.0.0.1:3307/online_exam_it?connectionTimeZone=UTC");
        if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("MySQL IT URL required");
        URI parsed = URI.create(url.substring(5));
        if (!"mysql".equals(parsed.getScheme()) || parsed.getHost() == null
                || !"/online_exam_it".equals(parsed.getPath()) || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("Repository tests require online_exam_it");
        }
        String password = System.getenv("MYSQL_IT_PASSWORD");
        if (password == null || password.isBlank()) password = System.getenv("MYSQL_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("MySQL IT password required");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("MYSQL_IT_USER", "online_exam"), password);
        try (Connection connection = dataSource.getConnection()) {
            if (!"online_exam_it".equals(connection.getCatalog())) {
                throw new IllegalStateException("Unexpected integration test schema");
            }
        }
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        users = new UserRepository(jdbc);
        exams = new ExamRepository(jdbc);
        questions = new QuestionRepository(jdbc);
        options = new QuestionOptionRepository(jdbc);
        attempts = new AttemptRepository(jdbc);
        answers = new AnswerRepository(jdbc);
        events = new SecurityEventRepository(jdbc);
    }

    @Test
    void allRepositoriesRoundTripThroughMySql() {
        transactions.executeWithoutResult(transaction -> {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            long instructorId = users.create(user("teacher_" + suffix, Role.INSTRUCTOR));
            long studentId = users.create(user("student_" + suffix, Role.STUDENT));
            assertThat(users.findByUsername("teacher_" + suffix)).get()
                    .extracting(User::id).isEqualTo(instructorId);
            assertThat(users.findById(studentId)).get().extracting(User::createdAt).isEqualTo(CREATED);
            assertThat(users.updateEnabled(studentId, false)).isEqualTo(1);
            assertThat(users.findById(studentId)).get().extracting(User::enabled).isEqualTo(false);
            assertThat(users.updateEnabled(studentId, true)).isEqualTo(1);

            long examId = exams.create(exam(instructorId, "Repository exam", ExamStatus.DRAFT, null));
            assertThat(exams.findByIdForUpdate(examId)).get().extracting(Exam::title)
                    .isEqualTo("Repository exam");
            assertThat(exams.findByInstructor(instructorId)).extracting(Exam::id).contains(examId);
            assertThat(exams.update(examWithId(examId, instructorId, "Published exam",
                    ExamStatus.PUBLISHED, CREATED))).isEqualTo(1);
            assertThat(exams.findPublished()).extracting(Exam::id).contains(examId);

            long questionId = questions.create(new Question(0, examId, "What is 2 + 2?", 7, 0));
            assertThat(questions.findById(questionId)).get().extracting(Question::marks).isEqualTo(7);
            assertThat(questions.update(new Question(questionId, examId, "Choose four", 7, 0))).isEqualTo(1);
            assertThat(questions.findByExam(examId)).extracting(Question::text).contains("Choose four");
            long correctId = options.create(new QuestionOption(0, questionId, "4", true, 0));
            long wrongId = options.create(new QuestionOption(0, questionId, "5", false, 1));
            assertThat(options.findById(correctId)).get().extracting(QuestionOption::correct)
                    .isEqualTo(true);
            assertThat(options.update(new QuestionOption(wrongId, questionId, "6", false, 1)))
                    .isEqualTo(1);
            assertThat(options.findByQuestion(questionId)).extracting(QuestionOption::text)
                    .containsExactly("4", "6");

            Instant deadline = CREATED.plusSeconds(900);
            long attemptId = attempts.create(new Attempt(0, examId, studentId,
                    AttemptStatus.IN_PROGRESS, CREATED, deadline, null, null, null));
            assertThat(attempts.findByIdForUpdate(attemptId)).get().extracting(Attempt::deadlineAt)
                    .isEqualTo(deadline);
            assertThat(attempts.findByExamAndStudent(examId, studentId)).get()
                    .extracting(Attempt::id).isEqualTo(attemptId);
            assertThat(attempts.findByStudent(studentId)).extracting(Attempt::id).contains(attemptId);
            assertThat(attempts.findByExam(examId)).extracting(Attempt::id).contains(attemptId);
            assertThat(attempts.findOverdue(deadline, 10)).extracting(Attempt::id).contains(attemptId);

            Instant firstSave = CREATED.plusSeconds(30);
            Instant secondSave = CREATED.plusSeconds(45);
            assertThat(answers.create(new Answer(attemptId, examId, questionId,
                    correctId, 1, firstSave))).isEqualTo(1);
            assertThat(answers.appendRevision(new AnswerRevision(attemptId, questionId,
                    1, correctId, firstSave))).isEqualTo(1);
            assertThat(answers.updateIfRevision(new Answer(attemptId, examId, questionId,
                    null, 2, secondSave), 1)).isEqualTo(1);
            assertThat(answers.appendRevision(new AnswerRevision(attemptId, questionId,
                    2, null, secondSave))).isEqualTo(1);
            assertThat(answers.updateIfRevision(new Answer(attemptId, examId, questionId,
                    wrongId, 3, secondSave), 1)).isZero();
            assertThat(answers.find(attemptId, questionId)).get()
                    .extracting(Answer::revision).isEqualTo(2);
            assertThat(answers.findByAttempt(attemptId)).hasSize(1);
            assertThat(answers.findRevisions(attemptId, questionId))
                    .extracting(AnswerRevision::revision).containsExactly(1, 2);

            long eventId = events.create(new SecurityEvent(0, studentId, examId, attemptId,
                    "TEST_EVENT", secondSave, "Repository integration fixture"));
            assertThat(events.findByUser(studentId)).extracting(SecurityEvent::id).contains(eventId);
            assertThat(events.findByExam(examId)).extracting(SecurityEvent::id).contains(eventId);
            assertThat(attempts.finalizeAttempt(attemptId, AttemptStatus.SUBMITTED,
                    deadline, 0, 7)).isEqualTo(1);
            assertThat(attempts.finalizeAttempt(attemptId, AttemptStatus.TIMED_OUT,
                    deadline, 7, 7)).isZero();
            assertThat(attempts.findById(attemptId)).get().extracting(Attempt::score).isEqualTo(0);
            assertThat(attempts.findOverdue(deadline.plusSeconds(1), 10))
                    .extracting(Attempt::id).doesNotContain(attemptId);
            transaction.setRollbackOnly();
        });
    }

    @Test
    void paperQueriesKeepExamScopeAndOptionOrder() {
        transactions.executeWithoutResult(transaction -> {
            long teacher = users.create(user("batch_" + UUID.randomUUID(), Role.INSTRUCTOR));
            long exam = exams.create(exam(teacher, "Batch paper", ExamStatus.DRAFT, null));
            long other = exams.create(exam(teacher, "Other paper", ExamStatus.DRAFT, null));
            long first = questions.create(new Question(0, exam, "First", 2, 0));
            long second = questions.create(new Question(0, exam, "Second", 5, 1));
            long foreign = questions.create(new Question(0, other, "Other", 9, 0));
            options.create(new QuestionOption(0, first, "Later", false, 1));
            options.create(new QuestionOption(0, first, "Earlier", true, 0));
            options.create(new QuestionOption(0, second, "Second choice", true, 0));
            options.create(new QuestionOption(0, foreign, "Other choice", true, 0));

            var choices = options.findByExam(exam);
            assertThat(choices).containsOnlyKeys(first, second);
            assertThat(choices.get(first)).extracting(QuestionOption::text)
                    .containsExactly("Earlier", "Later");
            assertThat(choices.get(second)).extracting(QuestionOption::text)
                    .containsExactly("Second choice");
            assertThat(options.findByExam(Long.MAX_VALUE)).isEmpty();
            var stats = questions.statsByExam();
            assertThat(stats.get(exam)).isEqualTo(new QuestionRepository.Stats(2, 7));
            assertThat(stats.get(other)).isEqualTo(new QuestionRepository.Stats(1, 9));
            assertThat(questions.stats(exam)).isEqualTo(new QuestionRepository.Stats(2, 7));
            assertThat(questions.stats(other)).isEqualTo(new QuestionRepository.Stats(1, 9));
            assertThat(questions.stats(Long.MAX_VALUE)).isEqualTo(new QuestionRepository.Stats(0, 0));
            transaction.setRollbackOnly();
        });
    }

    @Test
    void draftDeleteCascadesAndExplicitRollbackLeavesNoFixture() {
        String username = "rollback_" + UUID.randomUUID().toString().replace("-", "");
        transactions.executeWithoutResult(transaction -> {
            long instructorId = users.create(user(username, Role.INSTRUCTOR));
            long examId = exams.create(exam(instructorId, "Temporary", ExamStatus.DRAFT, null));
            long questionId = questions.create(new Question(0, examId, "Temporary question", 1, 0));
            long optionId = options.create(new QuestionOption(0, questionId, "Temporary option", false, 0));
            assertThat(options.deleteById(optionId)).isEqualTo(1);
            assertThat(options.findById(optionId)).isEmpty();
            options.create(new QuestionOption(0, questionId, "Other option", false, 0));
            assertThat(questions.deleteById(questionId)).isEqualTo(1);
            assertThat(options.findByQuestion(questionId)).isEmpty();
            questions.create(new Question(0, examId, "Another question", 1, 0));
            assertThat(exams.deleteById(examId)).isEqualTo(1);
            assertThat(questions.findByExam(examId)).isEmpty();
            transaction.setRollbackOnly();
        });
        assertThat(users.findByUsername(username)).isEmpty();
    }

    @Test
    void exceptionRollsBackDatabaseWrites() {
        String username = "exception_" + UUID.randomUUID().toString().replace("-", "");
        assertThatThrownBy(() -> transactions.executeWithoutResult(transaction -> {
            users.create(user(username, Role.STUDENT));
            throw new IllegalStateException("Force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Force rollback");
        assertThat(users.findByUsername(username)).isEmpty();
    }

    private static User user(String username, Role role) {
        return new User(0, username, "Repository fixture", "test-only-non-login-hash",
                role, true, CREATED);
    }

    private static Exam exam(long instructorId, String title, ExamStatus status, Instant publishedAt) {
        return examWithId(0, instructorId, title, status, publishedAt);
    }

    private static Exam examWithId(long id, long instructorId, String title,
                                   ExamStatus status, Instant publishedAt) {
        return new Exam(id, instructorId, title, "Repository fixture", 15,
                OPENS, CLOSES, status, publishedAt);
    }
}

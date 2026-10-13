package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.service.AccessControl;
import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

class AccessControlIT {
    private static final Instant START = Instant.parse("2026-09-29T00:00:00Z");
    private static UserRepository users;
    private static ExamRepository exams;
    private static AttemptRepository attempts;
    private static AccessControl access;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void connectToDisposableMySql() throws Exception {
        String url = System.getenv().getOrDefault("MYSQL_IT_DB_URL",
                "jdbc:mysql://127.0.0.1:3307/online_exam_it?connectionTimeZone=UTC");
        if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("MySQL IT URL required");
        URI parsed = URI.create(url.substring(5));
        if (!"mysql".equals(parsed.getScheme()) || parsed.getHost() == null
                || !"/online_exam_it".equals(parsed.getPath()) || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("Access checks require online_exam_it");
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
        users = new UserRepository(jdbc);
        exams = new ExamRepository(jdbc);
        attempts = new AttemptRepository(jdbc);
        access = new AccessControl(exams, attempts);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void instructorCanReadAndLockOnlyTheirOwnExam() {
        transactions.executeWithoutResult(transaction -> {
            long first = user(Role.INSTRUCTOR);
            long second = user(Role.INSTRUCTOR);
            long firstExam = exam(first);
            long secondExam = exam(second);

            actAs(first, Role.INSTRUCTOR);
            assertThat(access.ownedExam(firstExam).id()).isEqualTo(firstExam);
            assertThat(access.ownedExamForUpdate(firstExam).id()).isEqualTo(firstExam);
            expect(HttpStatus.NOT_FOUND, () -> access.ownedExam(secondExam));
            expect(HttpStatus.NOT_FOUND, () -> access.ownedExamForUpdate(secondExam));
            expect(HttpStatus.NOT_FOUND, () -> access.ownedExam(Long.MAX_VALUE));

            actAs(first, Role.STUDENT);
            expect(HttpStatus.FORBIDDEN, () -> access.ownedExam(firstExam));
            SecurityContextHolder.clearContext();
            expect(HttpStatus.UNAUTHORIZED, () -> access.ownedExam(firstExam));
            transaction.setRollbackOnly();
        });
    }

    @Test
    void studentCanReadAndLockOnlyTheirOwnAttempt() {
        transactions.executeWithoutResult(transaction -> {
            long instructor = user(Role.INSTRUCTOR);
            long firstStudent = user(Role.STUDENT);
            long secondStudent = user(Role.STUDENT);
            long exam = exam(instructor);
            long firstAttempt = attempt(exam, firstStudent);
            long secondAttempt = attempt(exam, secondStudent);

            actAs(firstStudent, Role.STUDENT);
            assertThat(access.ownAttempt(firstAttempt).id()).isEqualTo(firstAttempt);
            assertThat(access.ownAttemptForUpdate(firstAttempt).id()).isEqualTo(firstAttempt);
            expect(HttpStatus.NOT_FOUND, () -> access.ownAttempt(secondAttempt));
            expect(HttpStatus.NOT_FOUND, () -> access.ownAttemptForUpdate(secondAttempt));
            expect(HttpStatus.NOT_FOUND, () -> access.ownAttempt(Long.MAX_VALUE));

            actAs(instructor, Role.INSTRUCTOR);
            expect(HttpStatus.FORBIDDEN, () -> access.ownAttempt(firstAttempt));
            SecurityContextHolder.clearContext();
            expect(HttpStatus.UNAUTHORIZED, () -> access.ownAttempt(firstAttempt));
            transaction.setRollbackOnly();
        });
    }

    private static void expect(HttpStatus status, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.status()).isEqualTo(status));
    }

    private static void actAs(long id, Role role) {
        ExamPrincipal principal = new ExamPrincipal(id, "fixture_" + id, "Fixture",
                "{bcrypt}unused", role, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static long user(Role role) {
        String username = "access_" + UUID.randomUUID().toString().replace("-", "");
        return users.create(new User(0, username, "Access fixture", "{bcrypt}unused",
                role, true, START));
    }

    private static long exam(long instructorId) {
        return exams.create(new Exam(0, instructorId, "Access fixture", "", 30,
                START.minusSeconds(3600), START.plusSeconds(3600), ExamStatus.DRAFT, null));
    }

    private static long attempt(long examId, long studentId) {
        return attempts.create(new Attempt(0, examId, studentId, AttemptStatus.IN_PROGRESS,
                START, START.plusSeconds(1800), null, null, null));
    }
}

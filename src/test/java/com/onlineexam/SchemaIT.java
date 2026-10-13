package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SchemaIT {
    @Test
    void migrationHasTablesKeysAndIndexes() throws Exception {
        try (Connection connection = connect()) {
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1"))
                    .isEqualTo(1);
            assertThat(names(connection,
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"))
                    .contains("users", "exams", "questions", "question_options", "attempts",
                            "answers", "answer_revisions", "security_events", "flyway_schema_history");
            assertThat(names(connection,
                    "SELECT index_name FROM information_schema.statistics WHERE table_schema = DATABASE()"))
                    .contains("uq_users_username", "uq_attempts_exam_student", "uq_options_one_correct",
                            "ix_attempts_expiry", "ix_exams_availability", "ix_security_events_exam_time");
            assertThat(names(connection,
                    "SELECT constraint_name FROM information_schema.referential_constraints "
                            + "WHERE constraint_schema = DATABASE()"))
                    .contains("fk_answers_attempt_exam", "fk_answers_question_exam",
                            "fk_answers_option_question", "fk_answer_revisions_answer",
                            "fk_attempts_exam", "fk_security_events_attempt");
        }
    }

    @Test
    void databaseRejectsDuplicateAttemptForeignAnswerAndInvalidMarks() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String studentName = "schema_student_" + suffix;
            try {
                long instructor = insert(connection,
                        "INSERT INTO users(username,display_name,password_hash,role,enabled,created_at) "
                                + "VALUES (?,?,?,?,?,?)",
                        "schema_teacher_" + suffix, "Schema Teacher", "test-only-non-login-hash",
                        "INSTRUCTOR", true, time("2026-01-01T00:00:00Z"));
                long student = insert(connection,
                        "INSERT INTO users(username,display_name,password_hash,role,enabled,created_at) "
                                + "VALUES (?,?,?,?,?,?)",
                        studentName, "Schema Student", "test-only-non-login-hash",
                        "STUDENT", true, time("2026-01-01T00:00:00Z"));
                long examA = exam(connection, instructor, "A");
                long examB = exam(connection, instructor, "B");
                long questionA = question(connection, examA, 0, 5);
                long questionB = question(connection, examB, 0, 7);
                long optionA = option(connection, questionA, 0, true);
                long optionB = option(connection, questionB, 0, true);

                assertSqlError(3819, () -> question(connection, examA, 1, 0));
                assertSqlError(1062, () -> option(connection, questionA, 1, true));

                long attempt = insert(connection,
                        "INSERT INTO attempts(exam_id,student_id,status,started_at,deadline_at) "
                                + "VALUES (?,?,'IN_PROGRESS',?,?)",
                        examA, student, time("2026-09-29T00:00:00Z"), time("2026-09-29T00:30:00Z"));
                assertSqlError(1062, () -> insert(connection,
                        "INSERT INTO attempts(exam_id,student_id,status,started_at,deadline_at) "
                                + "VALUES (?,?,'IN_PROGRESS',?,?)",
                        examA, student, time("2026-09-29T00:00:00Z"), time("2026-09-29T00:30:00Z")));

                String answerSql = "INSERT INTO answers(attempt_id,exam_id,question_id,selected_option_id,revision,saved_at) "
                        + "VALUES (?,?,?,?,?,?)";
                Timestamp saved = time("2026-09-29T00:01:00Z");
                assertSqlError(1452, () -> insert(connection, answerSql,
                        attempt, examA, questionB, null, 1, saved));
                assertSqlError(1452, () -> insert(connection, answerSql,
                        attempt, examA, questionA, optionB, 1, saved));
                insert(connection, answerSql, attempt, examA, questionA, optionA, 1, saved);
                insert(connection,
                        "INSERT INTO answer_revisions(attempt_id,question_id,revision,selected_option_id,saved_at) "
                                + "VALUES (?,?,?,?,?)",
                        attempt, questionA, 1, optionA, saved);
                assertSqlError(1062, () -> insert(connection,
                        "INSERT INTO answer_revisions(attempt_id,question_id,revision,selected_option_id,saved_at) "
                                + "VALUES (?,?,?,?,?)",
                        attempt, questionA, 1, optionA, saved));
            } finally {
                connection.rollback();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM users WHERE username = ?")) {
                statement.setString(1, studentName);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }

    private static long exam(Connection connection, long instructor, String title) throws SQLException {
        return insert(connection,
                "INSERT INTO exams(instructor_id,title,description,duration_minutes,opens_at,closes_at,status) "
                        + "VALUES (?,?,?,30,?,?,'DRAFT')",
                instructor, title, "Schema fixture", time("2026-01-01T00:00:00Z"),
                time("2027-01-01T00:00:00Z"));
    }

    private static long question(Connection connection, long exam, int order, int marks) throws SQLException {
        return insert(connection,
                "INSERT INTO questions(exam_id,question_text,marks,display_order) VALUES (?,?,?,?)",
                exam, "Schema fixture question", marks, order);
    }

    private static long option(Connection connection, long question, int order, boolean correct) throws SQLException {
        return insert(connection,
                "INSERT INTO question_options(question_id,option_text,is_correct,display_order) VALUES (?,?,?,?)",
                question, "Schema fixture option", correct, order);
    }

    private static long insert(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    private static Set<String> names(Connection connection, String sql) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static Timestamp time(String instant) {
        return Timestamp.from(Instant.parse(instant));
    }

    private static Connection connect() throws Exception {
        String url = System.getenv().getOrDefault("MYSQL_IT_DB_URL",
                "jdbc:mysql://127.0.0.1:3307/online_exam_it?connectionTimeZone=UTC");
        if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("MySQL IT URL required");
        URI parsed = URI.create(url.substring(5));
        if (!"mysql".equals(parsed.getScheme()) || parsed.getHost() == null
                || !"/online_exam_it".equals(parsed.getPath()) || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("Schema tests require online_exam_it");
        }
        String password = System.getenv("MYSQL_IT_PASSWORD");
        if (password == null || password.isBlank()) password = System.getenv("MYSQL_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("MySQL IT password required");
        Connection connection = DriverManager.getConnection(url,
                System.getenv().getOrDefault("MYSQL_IT_USER", "online_exam"), password);
        if (!"online_exam_it".equals(connection.getCatalog())) {
            connection.close();
            throw new IllegalStateException("Unexpected schema for integration test");
        }
        return connection;
    }

    private static void assertSqlError(int expectedCode, SqlAction action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expectedCode));
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}

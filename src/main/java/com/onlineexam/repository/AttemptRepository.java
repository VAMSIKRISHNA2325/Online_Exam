package com.onlineexam.repository;

import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AttemptRepository {
    private static final String COLUMNS = "id, exam_id, student_id, status, started_at, deadline_at, "
            + "finalized_at, score, total_marks";
    private static final RowMapper<Attempt> MAPPER = AttemptRepository::map;
    private final JdbcTemplate jdbc;

    public AttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(Attempt attempt) {
        return JdbcValues.insert(jdbc,
                "INSERT INTO attempts(exam_id, student_id, status, started_at, deadline_at, "
                        + "finalized_at, score, total_marks) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                attempt.examId(), attempt.studentId(), attempt.status().name(),
                JdbcValues.utc(attempt.startedAt()), JdbcValues.utc(attempt.deadlineAt()),
                JdbcValues.utc(attempt.finalizedAt()), attempt.score(), attempt.totalMarks());
    }

    public Optional<Attempt> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attempts WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** Use inside each save or finalization transaction before checking state and deadline. */
    public Optional<Attempt> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attempts WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Attempt> findByExamAndStudent(long examId, long studentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attempts WHERE exam_id = ? AND student_id = ?",
                MAPPER, examId, studentId).stream().findFirst();
    }

    public List<Attempt> findByStudent(long studentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attempts WHERE student_id = ? ORDER BY id DESC",
                MAPPER, studentId);
    }

    public List<Attempt> findByExam(long examId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attempts WHERE exam_id = ? ORDER BY id",
                MAPPER, examId);
    }

    public List<Attempt> findOverdue(Instant now, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attempts "
                        + "WHERE status = 'IN_PROGRESS' AND deadline_at <= ? "
                        + "ORDER BY deadline_at, id LIMIT ?",
                MAPPER, JdbcValues.utc(now), limit);
    }

    public long maxOverdueId(Instant now) {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM attempts "
                + "WHERE status = 'IN_PROGRESS' AND deadline_at <= ?", Long.class, JdbcValues.utc(now));
        return id == null ? 0 : id;
    }

    /** A fixed upper bound lets each sweep wrap and retry failed rows despite new arrivals. */
    public List<Attempt> findOverdueAfter(Instant now, long afterId, long throughId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attempts "
                        + "WHERE status = 'IN_PROGRESS' AND deadline_at <= ? "
                        + "AND id > ? AND id <= ? "
                        + "ORDER BY id LIMIT ?",
                MAPPER, JdbcValues.utc(now), afterId, throughId, limit);
    }

    public int finalizeAttempt(long id, AttemptStatus terminalStatus, Instant finalizedAt,
                               int score, int totalMarks) {
        if (terminalStatus == AttemptStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("A terminal status is required");
        }
        return jdbc.update("UPDATE attempts SET status = ?, finalized_at = ?, score = ?, total_marks = ? "
                        + "WHERE id = ? AND status = 'IN_PROGRESS'",
                terminalStatus.name(), JdbcValues.utc(finalizedAt), score, totalMarks, id);
    }

    private static Attempt map(ResultSet rows, int rowNumber) throws SQLException {
        return new Attempt(rows.getLong("id"), rows.getLong("exam_id"), rows.getLong("student_id"),
                AttemptStatus.valueOf(rows.getString("status")), JdbcValues.instant(rows, "started_at"),
                JdbcValues.instant(rows, "deadline_at"), JdbcValues.instant(rows, "finalized_at"),
                rows.getObject("score", Integer.class), rows.getObject("total_marks", Integer.class));
    }
}

package com.onlineexam.repository;

import com.onlineexam.domain.SecurityEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SecurityEventRepository {
    private static final String COLUMNS =
            "id, user_id, exam_id, attempt_id, event_type, occurred_at, detail";
    private static final RowMapper<SecurityEvent> MAPPER = SecurityEventRepository::map;
    private final JdbcTemplate jdbc;

    public SecurityEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(SecurityEvent event) {
        return JdbcValues.insert(jdbc,
                "INSERT INTO security_events(user_id, exam_id, attempt_id, event_type, occurred_at, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                event.userId(), event.examId(), event.attemptId(), event.eventType(),
                JdbcValues.utc(event.occurredAt()), event.detail());
    }

    public List<SecurityEvent> findByUser(long userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM security_events "
                        + "WHERE user_id = ? ORDER BY occurred_at, id", MAPPER, userId);
    }

    public List<SecurityEvent> findByExam(long examId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM security_events "
                        + "WHERE exam_id = ? ORDER BY occurred_at, id", MAPPER, examId);
    }

    /** Account-level duplicate-login events are relevant only while the student is taking this exam. */
    public List<SecurityEvent> findDuplicateLoginsDuringExam(long examId) {
        return jdbc.query("SELECT se.id, se.user_id, se.exam_id, se.attempt_id, se.event_type, "
                        + "se.occurred_at, se.detail FROM security_events se "
                        + "JOIN attempts a ON a.student_id = se.user_id "
                        + "WHERE a.exam_id = ? AND se.exam_id IS NULL AND se.attempt_id IS NULL "
                        + "AND se.event_type = 'DUPLICATE_LOGIN' "
                        + "AND se.occurred_at >= a.started_at AND se.occurred_at <= a.deadline_at "
                        + "AND (a.finalized_at IS NULL OR se.occurred_at <= a.finalized_at) "
                        + "ORDER BY se.occurred_at, se.id", MAPPER, examId);
    }

    private static SecurityEvent map(ResultSet rows, int rowNumber) throws SQLException {
        return new SecurityEvent(rows.getLong("id"), rows.getLong("user_id"),
                rows.getObject("exam_id", Long.class), rows.getObject("attempt_id", Long.class),
                rows.getString("event_type"), JdbcValues.instant(rows, "occurred_at"),
                rows.getString("detail"));
    }
}

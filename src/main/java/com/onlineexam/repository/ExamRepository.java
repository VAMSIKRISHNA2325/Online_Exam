package com.onlineexam.repository;

import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ExamRepository {
    private static final String COLUMNS = "id, instructor_id, title, description, duration_minutes, "
            + "opens_at, closes_at, status, published_at";
    private static final RowMapper<Exam> MAPPER = ExamRepository::map;
    private final JdbcTemplate jdbc;

    public ExamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(Exam exam) {
        return JdbcValues.insert(jdbc,
                "INSERT INTO exams(instructor_id, title, description, duration_minutes, "
                        + "opens_at, closes_at, status, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                exam.instructorId(), exam.title(), exam.description(), exam.durationMinutes(),
                JdbcValues.utc(exam.opensAt()), JdbcValues.utc(exam.closesAt()),
                exam.status().name(), JdbcValues.utc(exam.publishedAt()));
    }

    public Optional<Exam> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exams WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** Use inside a transaction before reading or changing exam content or publication state. */
    public Optional<Exam> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exams WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public List<Exam> findByInstructor(long instructorId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exams WHERE instructor_id = ? ORDER BY id DESC",
                MAPPER, instructorId);
    }

    public List<Exam> findPublished() {
        return jdbc.query("SELECT " + COLUMNS + " FROM exams WHERE status = 'PUBLISHED' ORDER BY opens_at, id",
                MAPPER);
    }

    public int update(Exam exam) {
        return jdbc.update("UPDATE exams SET title = ?, description = ?, duration_minutes = ?, "
                        + "opens_at = ?, closes_at = ?, status = ?, published_at = ? WHERE id = ?",
                exam.title(), exam.description(), exam.durationMinutes(),
                JdbcValues.utc(exam.opensAt()), JdbcValues.utc(exam.closesAt()),
                exam.status().name(), JdbcValues.utc(exam.publishedAt()), exam.id());
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM exams WHERE id = ?", id);
    }

    private static Exam map(ResultSet rows, int rowNumber) throws SQLException {
        return new Exam(rows.getLong("id"), rows.getLong("instructor_id"),
                rows.getString("title"), rows.getString("description"),
                rows.getInt("duration_minutes"), JdbcValues.instant(rows, "opens_at"),
                JdbcValues.instant(rows, "closes_at"), ExamStatus.valueOf(rows.getString("status")),
                JdbcValues.instant(rows, "published_at"));
    }
}

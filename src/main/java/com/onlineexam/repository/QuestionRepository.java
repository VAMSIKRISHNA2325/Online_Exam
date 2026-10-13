package com.onlineexam.repository;

import com.onlineexam.domain.Question;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionRepository {
    private static final String COLUMNS = "id, exam_id, question_text, marks, display_order";
    private static final RowMapper<Question> MAPPER = QuestionRepository::map;
    private final JdbcTemplate jdbc;

    public QuestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(Question question) {
        return JdbcValues.insert(jdbc,
                "INSERT INTO questions(exam_id, question_text, marks, display_order) VALUES (?, ?, ?, ?)",
                question.examId(), question.text(), question.marks(), question.displayOrder());
    }

    public Optional<Question> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM questions WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<Question> findByExam(long examId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM questions WHERE exam_id = ? ORDER BY display_order, id",
                MAPPER, examId);
    }

    public Map<Long, Stats> statsByExam() {
        Map<Long, Stats> stats = new HashMap<>();
        jdbc.query("SELECT exam_id, COUNT(*) AS question_count, SUM(marks) AS total_marks "
                        + "FROM questions GROUP BY exam_id",
                rows -> {
                    stats.put(rows.getLong("exam_id"), new Stats(
                            Math.toIntExact(rows.getLong("question_count")), rows.getLong("total_marks")));
                });
        return stats;
    }

    public record Stats(int count, long marks) {}

    public Stats stats(long examId) {
        return jdbc.queryForObject("SELECT COUNT(*) AS question_count, "
                        + "COALESCE(SUM(marks), 0) AS total_marks FROM questions WHERE exam_id = ?",
                (rows, number) -> new Stats(Math.toIntExact(rows.getLong("question_count")),
                        rows.getLong("total_marks")), examId);
    }

    public int update(Question question) {
        return jdbc.update("UPDATE questions SET question_text = ?, marks = ?, display_order = ? WHERE id = ?",
                question.text(), question.marks(), question.displayOrder(), question.id());
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM questions WHERE id = ?", id);
    }

    private static Question map(ResultSet rows, int rowNumber) throws SQLException {
        return new Question(rows.getLong("id"), rows.getLong("exam_id"),
                rows.getString("question_text"), rows.getInt("marks"), rows.getInt("display_order"));
    }
}

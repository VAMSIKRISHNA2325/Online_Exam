package com.onlineexam.repository;

import com.onlineexam.domain.QuestionOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionOptionRepository {
    private static final String COLUMNS = "id, question_id, option_text, is_correct, display_order";
    private static final RowMapper<QuestionOption> MAPPER = QuestionOptionRepository::map;
    private final JdbcTemplate jdbc;

    public QuestionOptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(QuestionOption option) {
        return JdbcValues.insert(jdbc,
                "INSERT INTO question_options(question_id, option_text, is_correct, display_order) "
                        + "VALUES (?, ?, ?, ?)",
                option.questionId(), option.text(), option.correct(), option.displayOrder());
    }

    public Optional<QuestionOption> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM question_options WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<QuestionOption> findByQuestion(long questionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM question_options "
                        + "WHERE question_id = ? ORDER BY display_order, id", MAPPER, questionId);
    }

    public Map<Long, List<QuestionOption>> findByExam(long examId) {
        Map<Long, List<QuestionOption>> choices = new HashMap<>();
        jdbc.query("SELECT o.id, o.question_id, o.option_text, o.is_correct, o.display_order "
                        + "FROM question_options o JOIN questions q ON q.id = o.question_id "
                        + "WHERE q.exam_id = ? ORDER BY q.display_order, q.id, o.display_order, o.id",
                MAPPER, examId).forEach(option -> choices
                        .computeIfAbsent(option.questionId(), id -> new ArrayList<>()).add(option));
        return choices;
    }

    public int update(QuestionOption option) {
        return jdbc.update("UPDATE question_options SET option_text = ?, is_correct = ?, "
                        + "display_order = ? WHERE id = ?",
                option.text(), option.correct(), option.displayOrder(), option.id());
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM question_options WHERE id = ?", id);
    }

    private static QuestionOption map(ResultSet rows, int rowNumber) throws SQLException {
        return new QuestionOption(rows.getLong("id"), rows.getLong("question_id"),
                rows.getString("option_text"), rows.getBoolean("is_correct"),
                rows.getInt("display_order"));
    }
}

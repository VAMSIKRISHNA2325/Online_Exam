package com.onlineexam.repository;

import com.onlineexam.domain.Answer;
import com.onlineexam.domain.AnswerRevision;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AnswerRepository {
    private static final String COLUMNS = "attempt_id, exam_id, question_id, selected_option_id, "
            + "revision, saved_at";
    private static final RowMapper<Answer> ANSWER_MAPPER = AnswerRepository::mapAnswer;
    private static final RowMapper<AnswerRevision> REVISION_MAPPER = AnswerRepository::mapRevision;
    private final JdbcTemplate jdbc;

    public AnswerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int create(Answer answer) {
        return jdbc.update("INSERT INTO answers(attempt_id, exam_id, question_id, "
                        + "selected_option_id, revision, saved_at) VALUES (?, ?, ?, ?, ?, ?)",
                answer.attemptId(), answer.examId(), answer.questionId(),
                answer.selectedOptionId(), answer.revision(), JdbcValues.utc(answer.savedAt()));
    }

    public Optional<Answer> find(long attemptId, long questionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM answers WHERE attempt_id = ? AND question_id = ?",
                ANSWER_MAPPER, attemptId, questionId).stream().findFirst();
    }

    public List<Answer> findByAttempt(long attemptId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM answers WHERE attempt_id = ? ORDER BY question_id",
                ANSWER_MAPPER, attemptId);
    }

    /** Caller must hold the attempt row lock and append a matching history row in the same transaction. */
    public int updateIfRevision(Answer answer, int expectedRevision) {
        return jdbc.update("UPDATE answers SET selected_option_id = ?, revision = ?, saved_at = ? "
                        + "WHERE attempt_id = ? AND question_id = ? AND revision = ?",
                answer.selectedOptionId(), answer.revision(), JdbcValues.utc(answer.savedAt()),
                answer.attemptId(), answer.questionId(), expectedRevision);
    }

    public int appendRevision(AnswerRevision revision) {
        return jdbc.update("INSERT INTO answer_revisions(attempt_id, question_id, revision, "
                        + "selected_option_id, saved_at) VALUES (?, ?, ?, ?, ?)",
                revision.attemptId(), revision.questionId(), revision.revision(),
                revision.selectedOptionId(), JdbcValues.utc(revision.savedAt()));
    }

    public List<AnswerRevision> findRevisions(long attemptId, long questionId) {
        return jdbc.query("SELECT attempt_id, question_id, revision, selected_option_id, saved_at "
                        + "FROM answer_revisions WHERE attempt_id = ? AND question_id = ? ORDER BY revision",
                REVISION_MAPPER, attemptId, questionId);
    }

    private static Answer mapAnswer(ResultSet rows, int rowNumber) throws SQLException {
        return new Answer(rows.getLong("attempt_id"), rows.getLong("exam_id"),
                rows.getLong("question_id"), rows.getObject("selected_option_id", Long.class),
                rows.getInt("revision"), JdbcValues.instant(rows, "saved_at"));
    }

    private static AnswerRevision mapRevision(ResultSet rows, int rowNumber) throws SQLException {
        return new AnswerRevision(rows.getLong("attempt_id"), rows.getLong("question_id"),
                rows.getInt("revision"), rows.getObject("selected_option_id", Long.class),
                JdbcValues.instant(rows, "saved_at"));
    }
}

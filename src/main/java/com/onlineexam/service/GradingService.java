package com.onlineexam.service;

import com.onlineexam.domain.Answer;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Grades persisted answers against the server-held published paper. */
@Service
public class GradingService {
    private final AnswerEvaluator evaluator;

    public GradingService(AnswerEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public Grade grade(List<Question> paper, Map<Long, List<QuestionOption>> choices,
                       List<Answer> answers) {
        if (paper == null || paper.isEmpty()) {
            throw new IllegalStateException("A published paper must contain questions");
        }
        Set<Long> questionIds = new HashSet<>();
        for (Question question : paper) {
            if (question.marks() <= 0 || !questionIds.add(question.id())) {
                throw new IllegalStateException("Published paper has invalid questions or marks");
            }
        }
        Map<Long, Answer> saved = new HashMap<>();
        for (Answer answer : answers) {
            if (!questionIds.contains(answer.questionId())
                    || saved.putIfAbsent(answer.questionId(), answer) != null) {
                throw new IllegalStateException("Saved answers do not match the published paper");
            }
        }
        int score = 0;
        int totalMarks = 0;
        for (Question question : paper) {
            totalMarks = Math.addExact(totalMarks, question.marks());
            Answer answer = saved.get(question.id());
            if (evaluator.isCorrect(question, choices.get(question.id()),
                    answer == null ? null : answer.selectedOptionId())) {
                score = Math.addExact(score, question.marks());
            }
        }
        return new Grade(score, totalMarks);
    }

    public record Grade(int score, int totalMarks) {
        public Grade {
            if (totalMarks <= 0 || score < 0 || score > totalMarks) {
                throw new IllegalArgumentException("Invalid grade totals");
            }
        }

        public BigDecimal percentage() {
            return BigDecimal.valueOf(score).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalMarks), 2, RoundingMode.HALF_UP);
        }
    }
}

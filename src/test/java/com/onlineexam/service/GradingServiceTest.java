package com.onlineexam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onlineexam.domain.Answer;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GradingServiceTest {
    private final GradingService grading = new GradingService(new McqEvaluator());
    private final List<Question> paper = List.of(
            new Question(1, 9, "Q1", 2, 0),
            new Question(2, 9, "Q2", 3, 1),
            new Question(3, 9, "Q3", 5, 2),
            new Question(4, 9, "Q4", 10, 3));
    private final Map<Long, List<QuestionOption>> keys = Map.of(
            1L, options(1, 11, 12),
            2L, options(2, 21, 22),
            3L, options(3, 31, 32),
            4L, options(4, 41, 42));

    @Test
    void independentHandCalculatedScoreFixtures() {
        assertGrade(List.of(answer(1, 11L), answer(2, 21L), answer(3, 31L), answer(4, 41L)),
                20, "100.00");
        assertGrade(List.of(answer(1, 11L), answer(2, 22L), answer(3, null), answer(4, 41L)),
                12, "60.00");
        assertGrade(List.of(answer(1, 12L), answer(2, 22L), answer(3, 32L), answer(4, 42L)),
                0, "0.00");
        assertGrade(List.of(), 0, "0.00");
        assertGrade(List.of(answer(1, 11L), answer(2, null), answer(3, 31L)),
                7, "35.00");
    }

    @Test
    void rejectsCorruptKeysAndAnswerRelationshipsBeforeScoring() {
        assertThatThrownBy(() -> grading.grade(paper, Map.of(1L, options(1, 11, 12)), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> grading.grade(paper, keys, List.of(answer(1, 21L))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> grading.grade(paper, keys, List.of(answer(5, 51L))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> grading.grade(paper, keys, List.of(answer(1, 11L), answer(1, 11L))))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertGrade(List<Answer> saved, int expectedScore, String percentage) {
        GradingService.Grade grade = grading.grade(paper, keys, saved);
        assertThat(grade.score()).isEqualTo(expectedScore);
        assertThat(grade.totalMarks()).isEqualTo(20);
        assertThat(grade.percentage()).isEqualByComparingTo(new BigDecimal(percentage));
    }

    private static List<QuestionOption> options(long questionId, long correctId, long wrongId) {
        return List.of(new QuestionOption(correctId, questionId, "Correct", true, 0),
                new QuestionOption(wrongId, questionId, "Wrong", false, 1));
    }

    private static Answer answer(long questionId, Long optionId) {
        return new Answer(1, 9, questionId, optionId, 1, Instant.EPOCH);
    }
}

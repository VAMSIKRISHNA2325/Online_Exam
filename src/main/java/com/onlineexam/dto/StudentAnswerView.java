package com.onlineexam.dto;

import com.onlineexam.domain.Answer;
import java.time.Instant;

public record StudentAnswerView(long questionId, Long selectedOptionId,
                                int revision, Instant savedAt) {
    public static StudentAnswerView from(Answer answer) {
        return new StudentAnswerView(answer.questionId(), answer.selectedOptionId(),
                answer.revision(), answer.savedAt());
    }

    public static StudentAnswerView unsaved(long questionId) {
        return new StudentAnswerView(questionId, null, 0, null);
    }
}

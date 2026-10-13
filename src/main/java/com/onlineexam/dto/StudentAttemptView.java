package com.onlineexam.dto;

import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import java.time.Instant;
import java.util.List;

/** Student-only payload; option correctness and answer keys are never serialized. */
public record StudentAttemptView(long id, long examId, AttemptStatus status,
                                 Instant startedAt, Instant deadlineAt, Instant finalizedAt,
                                 Integer score, Integer totalMarks, Instant serverNow,
                                 List<QuestionState> questions) {
    public static StudentAttemptView from(Attempt attempt, Instant serverNow,
                                          List<QuestionState> questions) {
        return new StudentAttemptView(attempt.id(), attempt.examId(), attempt.status(),
                attempt.startedAt(), attempt.deadlineAt(), attempt.finalizedAt(),
                attempt.score(), attempt.totalMarks(), serverNow, questions);
    }

    public record QuestionState(long id, String text, int marks, int displayOrder,
                                List<QuestionViews.StudentOption> options,
                                Long selectedOptionId, int revision) {
    }
}

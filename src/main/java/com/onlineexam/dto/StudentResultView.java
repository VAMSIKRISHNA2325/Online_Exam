package com.onlineexam.dto;

import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Aggregate result only; the answer key never appears in a student response. */
public record StudentResultView(long attemptId, long examId, AttemptStatus status,
                                int score, int totalMarks, BigDecimal percentage,
                                Instant startedAt, Instant deadlineAt, Instant finalizedAt) {
    public static StudentResultView from(Attempt attempt) {
        if (attempt.status() == AttemptStatus.IN_PROGRESS || attempt.score() == null
                || attempt.totalMarks() == null || attempt.totalMarks() <= 0
                || attempt.finalizedAt() == null) {
            throw new IllegalStateException("Attempt has no final result");
        }
        BigDecimal percentage = BigDecimal.valueOf(attempt.score())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(attempt.totalMarks()), 2, RoundingMode.HALF_UP);
        return new StudentResultView(attempt.id(), attempt.examId(), attempt.status(),
                attempt.score(), attempt.totalMarks(), percentage,
                attempt.startedAt(), attempt.deadlineAt(), attempt.finalizedAt());
    }
}

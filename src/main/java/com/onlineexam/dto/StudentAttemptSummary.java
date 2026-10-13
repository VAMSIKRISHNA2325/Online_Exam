package com.onlineexam.dto;

import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import java.time.Instant;

public record StudentAttemptSummary(long id, long examId, AttemptStatus status,
                                    Instant startedAt, Instant deadlineAt, Instant finalizedAt,
                                    Integer score, Integer totalMarks) {
    public static StudentAttemptSummary from(Attempt attempt) {
        return new StudentAttemptSummary(attempt.id(), attempt.examId(), attempt.status(),
                attempt.startedAt(), attempt.deadlineAt(), attempt.finalizedAt(),
                attempt.score(), attempt.totalMarks());
    }
}

package com.onlineexam.domain;

import java.time.Instant;

public record Attempt(long id, long examId, long studentId, AttemptStatus status,
                      Instant startedAt, Instant deadlineAt, Instant finalizedAt,
                      Integer score, Integer totalMarks) {
}

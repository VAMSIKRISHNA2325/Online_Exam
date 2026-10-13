package com.onlineexam.domain;

import java.time.Instant;

public record Answer(long attemptId, long examId, long questionId,
                     Long selectedOptionId, int revision, Instant savedAt) {
}

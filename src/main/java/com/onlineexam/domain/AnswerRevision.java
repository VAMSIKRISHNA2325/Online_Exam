package com.onlineexam.domain;

import java.time.Instant;

public record AnswerRevision(long attemptId, long questionId, int revision,
                             Long selectedOptionId, Instant savedAt) {
}

package com.onlineexam.domain;

import java.time.Instant;

public record SecurityEvent(long id, long userId, Long examId, Long attemptId,
                            String eventType, Instant occurredAt, String detail) {
}

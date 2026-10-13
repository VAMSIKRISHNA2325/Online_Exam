package com.onlineexam.dto;

import java.time.Instant;

/** Exam-scoped event metadata. Free-form event detail is intentionally not returned. */
public record InstructorEventView(long id, Long examId, long actorId, String actorUsername,
                                  Long attemptId, String eventType, Instant occurredAt) {
}

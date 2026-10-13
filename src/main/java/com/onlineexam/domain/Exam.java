package com.onlineexam.domain;

import java.time.Instant;

public record Exam(long id, long instructorId, String title, String description,
                   int durationMinutes, Instant opensAt, Instant closesAt,
                   ExamStatus status, Instant publishedAt) {
}

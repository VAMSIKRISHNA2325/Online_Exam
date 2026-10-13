package com.onlineexam.dto;

import com.onlineexam.domain.Exam;
import com.onlineexam.service.ExamAvailabilityPolicy.Availability;
import java.time.Instant;

/** Catalog metadata only; questions and answer keys are returned after an authorized start. */
public record StudentExamView(long id, String title, String description, int durationMinutes,
                              Instant opensAt, Instant closesAt, Availability availability,
                              int questionCount, long maximumMarks) {
    public static StudentExamView from(Exam exam, Availability availability,
                                       int questionCount, long maximumMarks) {
        return new StudentExamView(exam.id(), exam.title(), exam.description(),
                exam.durationMinutes(), exam.opensAt(), exam.closesAt(), availability,
                questionCount, maximumMarks);
    }
}

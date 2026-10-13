package com.onlineexam.dto;

import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import java.time.Instant;
import java.util.List;

public final class AuthoringViews {
    private AuthoringViews() {
    }

    public record ExamView(long id, String title, String description, int durationMinutes,
                           Instant opensAt, Instant closesAt, ExamStatus status,
                           Instant publishedAt) {
        public static ExamView from(Exam exam) {
            return new ExamView(exam.id(), exam.title(), exam.description(),
                    exam.durationMinutes(), exam.opensAt(), exam.closesAt(),
                    exam.status(), exam.publishedAt());
        }
    }

    public record ExamDetail(ExamView exam,
                             List<QuestionViews.InstructorQuestion> questions) {
    }
}

package com.onlineexam.service;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Apply this to a new attempt after checking whether that student already has one. */
@Component
public class ExamAvailabilityPolicy {
    public enum Availability {
        UPCOMING,
        OPEN,
        CLOSED
    }

    public Availability state(Exam exam, Instant now) {
        if (exam.status() != ExamStatus.PUBLISHED) {
            throw new IllegalArgumentException("Availability requires a published exam");
        }
        if (now.isBefore(exam.opensAt())) return Availability.UPCOMING;
        if (!now.isBefore(exam.closesAt())) return Availability.CLOSED;
        return Availability.OPEN;
    }

    public void requireOpenForNewAttempt(Exam exam, Instant now) {
        if (exam.status() != ExamStatus.PUBLISHED) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Exam was not found.");
        }
        switch (state(exam, now)) {
            case UPCOMING -> throw new ApiException(HttpStatus.CONFLICT, "EXAM_NOT_OPEN",
                    "This exam has not opened yet.");
            case CLOSED -> throw new ApiException(HttpStatus.CONFLICT, "EXAM_CLOSED",
                    "This exam is closed.");
            case OPEN -> {
            }
        }
    }
}

package com.onlineexam.service;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate transactional bean lets a duplicate insert roll back before the winner is read. */
@Service
public class AttemptStartTransaction {
    private final ExamRepository exams;
    private final AttemptRepository attempts;
    private final ExamAvailabilityPolicy availability;
    private final Clock clock;

    public AttemptStartTransaction(ExamRepository exams, AttemptRepository attempts,
                                   ExamAvailabilityPolicy availability, Clock clock) {
        this.exams = exams;
        this.attempts = attempts;
        this.availability = availability;
        this.clock = clock;
    }

    @Transactional
    public Attempt startOrResume(long examId, long studentId) {
        // Serialize new starts with each other and with publication on the exam row.
        Exam exam = exams.findByIdForUpdate(examId)
                .orElseThrow(AttemptStartTransaction::missingExam);
        if (exam.status() != ExamStatus.PUBLISHED) throw missingExam();
        Attempt existing = attempts.findByExamAndStudent(examId, studentId).orElse(null);
        if (existing != null) return existing;

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        availability.requireOpenForNewAttempt(exam, now);
        Instant timedLimit = now.plusSeconds(Math.multiplyExact(exam.durationMinutes(), 60L));
        Instant deadline = timedLimit.isBefore(exam.closesAt()) ? timedLimit : exam.closesAt();
        long id = attempts.create(new Attempt(0, examId, studentId, AttemptStatus.IN_PROGRESS,
                now, deadline, null, null, null));
        return attempts.findById(id).orElseThrow();
    }

    /** Runs in a fresh transaction after a conflicting insert transaction has rolled back. */
    @Transactional(readOnly = true)
    public Attempt findWinner(long examId, long studentId) {
        return attempts.findByExamAndStudent(examId, studentId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "START_CONFLICT",
                        "The attempt could not be started. Try again."));
    }

    private static ApiException missingExam() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Exam was not found.");
    }
}

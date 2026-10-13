package com.onlineexam.service;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.SecurityEvent;
import com.onlineexam.domain.User;
import com.onlineexam.dto.InstructorEventView;
import com.onlineexam.dto.InstructorResultView;
import com.onlineexam.dto.StudentResultView;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.SecurityEventRepository;
import com.onlineexam.repository.UserRepository;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ResultQueryService {
    private final AccessControl access;
    private final AttemptRepository attempts;
    private final SecurityEventRepository events;
    private final UserRepository users;
    private final FinalizationService finalization;
    private final Clock clock;

    public ResultQueryService(AccessControl access, AttemptRepository attempts,
                              SecurityEventRepository events, UserRepository users,
                              FinalizationService finalization, Clock clock) {
        this.access = access;
        this.attempts = attempts;
        this.events = events;
        this.users = users;
        this.finalization = finalization;
        this.clock = clock;
    }

    public StudentResultView studentResult(long attemptId) {
        Attempt attempt = access.ownAttempt(attemptId);
        if (attempt.status() != AttemptStatus.IN_PROGRESS) return StudentResultView.from(attempt);
        return finalization.finalizeIfExpired(attemptId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "RESULT_NOT_READY",
                        "The attempt has not been finalized."));
    }

    public List<InstructorResultView> instructorResults(long examId) {
        access.ownedExam(examId);
        // Each finalizer call has its own attempt-row transaction and rechecks the deadline.
        attempts.findByExam(examId).stream()
                .filter(attempt -> attempt.status() == AttemptStatus.IN_PROGRESS)
                .filter(attempt -> !clock.instant().isBefore(attempt.deadlineAt()))
                .forEach(attempt -> finalization.finalizeIfExpired(attempt.id()));
        return attempts.findByExam(examId).stream()
                .filter(attempt -> attempt.status() != AttemptStatus.IN_PROGRESS)
                .map(attempt -> {
                    User student = users.findById(attempt.studentId()).orElseThrow();
                    return new InstructorResultView(student.id(), student.username(),
                            student.displayName(), StudentResultView.from(attempt));
                }).toList();
    }

    public List<InstructorEventView> instructorEvents(long examId) {
        access.ownedExam(examId);
        return Stream.concat(events.findByExam(examId).stream(),
                        events.findDuplicateLoginsDuringExam(examId).stream())
                .sorted(Comparator.comparing(SecurityEvent::occurredAt)
                        .thenComparingLong(SecurityEvent::id))
                .map(this::eventView).toList();
    }

    private InstructorEventView eventView(SecurityEvent event) {
        User actor = users.findById(event.userId()).orElseThrow();
        return new InstructorEventView(event.id(), event.examId(), actor.id(), actor.username(),
                event.attemptId(), event.eventType(), event.occurredAt());
    }
}

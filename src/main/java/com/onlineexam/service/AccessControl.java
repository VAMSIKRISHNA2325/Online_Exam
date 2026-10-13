package com.onlineexam.service;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.Role;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.security.ExamPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Resolves the actor from the server-side session and hides inaccessible object IDs. */
@Service
public class AccessControl {
    private final ExamRepository exams;
    private final AttemptRepository attempts;

    public AccessControl(ExamRepository exams, AttemptRepository attempts) {
        this.exams = exams;
        this.attempts = attempts;
    }

    public ExamPrincipal actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof ExamPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue.");
        }
        return principal;
    }

    public ExamPrincipal instructor() {
        return requireRole(Role.INSTRUCTOR);
    }

    public ExamPrincipal student() {
        return requireRole(Role.STUDENT);
    }

    public Exam ownedExam(long examId) {
        long instructorId = instructor().id();
        Exam exam = exams.findById(examId)
                .orElseThrow(() -> inaccessible("Exam"));
        if (exam.instructorId() != instructorId) throw inaccessible("Exam");
        return exam;
    }

    /** Call inside a transaction before any exam, question or option mutation. */
    public Exam ownedExamForUpdate(long examId) {
        long instructorId = instructor().id();
        Exam exam = exams.findByIdForUpdate(examId)
                .orElseThrow(() -> inaccessible("Exam"));
        if (exam.instructorId() != instructorId) throw inaccessible("Exam");
        return exam;
    }

    public Attempt ownAttempt(long attemptId) {
        long studentId = student().id();
        Attempt attempt = attempts.findById(attemptId)
                .orElseThrow(() -> inaccessible("Attempt"));
        if (attempt.studentId() != studentId) throw inaccessible("Attempt");
        return attempt;
    }

    /** Call inside a transaction before checking deadline/state or changing an answer/result. */
    public Attempt ownAttemptForUpdate(long attemptId) {
        long studentId = student().id();
        Attempt attempt = attempts.findByIdForUpdate(attemptId)
                .orElseThrow(() -> inaccessible("Attempt"));
        if (attempt.studentId() != studentId) throw inaccessible("Attempt");
        return attempt;
    }

    private ExamPrincipal requireRole(Role role) {
        ExamPrincipal principal = actor();
        if (principal.role() != role) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "The request is not allowed.");
        }
        return principal;
    }

    private static ApiException inaccessible(String type) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", type + " was not found.");
    }
}

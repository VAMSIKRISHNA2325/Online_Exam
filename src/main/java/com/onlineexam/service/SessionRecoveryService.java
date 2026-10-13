package com.onlineexam.service;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.SecurityEvent;
import com.onlineexam.domain.User;
import com.onlineexam.dto.ActiveAttemptView;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.SecurityEventRepository;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.security.LoginRegistrationLock;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionRecoveryService {
    private final AccessControl access;
    private final AttemptRepository attempts;
    private final ExamRepository exams;
    private final UserRepository users;
    private final SecurityEventRepository events;
    private final SessionRegistry sessions;
    private final LoginRegistrationLock loginLock;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public SessionRecoveryService(AccessControl access, AttemptRepository attempts,
                                  ExamRepository exams, UserRepository users,
                                  SecurityEventRepository events, SessionRegistry sessions,
                                  LoginRegistrationLock loginLock,
                                  PlatformTransactionManager transactions, Clock clock) {
        this.access = access;
        this.attempts = attempts;
        this.exams = exams;
        this.users = users;
        this.events = events;
        this.sessions = sessions;
        this.loginLock = loginLock;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    public List<ActiveAttemptView> activeAttempts(long examId) {
        access.ownedExam(examId);
        Instant now = now();
        return attempts.findByExam(examId).stream()
                .filter(attempt -> current(attempt, now))
                .map(attempt -> {
                    User student = users.findById(attempt.studentId()).orElseThrow();
                    return new ActiveAttemptView(attempt.id(), student.id(), student.username(),
                            student.displayName(), attempt.deadlineAt());
                }).toList();
    }

    public void recover(long attemptId) {
        loginLock.lock();
        try {
            // Commit the audit before expiring a session. A failed DB write leaves it intact.
            ExamPrincipal student = transaction.execute(ignored -> authorizeAndAudit(attemptId));
            if (student == null) throw new IllegalStateException("Recovery transaction returned no student");
            for (SessionInformation session : sessions.getAllSessions(student, false)) {
                session.expireNow();
            }
        } finally {
            loginLock.unlock();
        }
    }

    private ExamPrincipal authorizeAndAudit(long attemptId) {
        long instructorId = access.instructor().id();
        Attempt attempt = attempts.findByIdForUpdate(attemptId)
                .orElseThrow(SessionRecoveryService::missingAttempt);
        Exam exam = access.ownedExam(attempt.examId());
        Instant now = now();
        if (!current(attempt, now)) {
            throw new ApiException(HttpStatus.CONFLICT, "RECOVERY_UNAVAILABLE",
                    "Only a current attempt can be recovered.");
        }
        for (Attempt other : attempts.findByStudent(attempt.studentId())) {
            if (other.id() == attempt.id() || !current(other, now)) continue;
            Exam otherExam = exams.findById(other.examId()).orElseThrow();
            if (otherExam.instructorId() != instructorId) {
                throw new ApiException(HttpStatus.CONFLICT, "RECOVERY_CONFLICT",
                        "This student has another active exam. Recovery is unavailable.");
            }
        }
        User student = users.findById(attempt.studentId()).orElseThrow();
        events.create(new SecurityEvent(0, instructorId, exam.id(), attempt.id(),
                "SESSION_RECOVERY", now, "Instructor supervised student session recovery"));
        return ExamPrincipal.from(student);
    }

    private static ApiException missingAttempt() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt was not found.");
    }

    private static boolean current(Attempt attempt, Instant now) {
        return attempt.status() == AttemptStatus.IN_PROGRESS && now.isBefore(attempt.deadlineAt());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}

package com.onlineexam.service;

import com.onlineexam.domain.Answer;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.dto.StudentResultView;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The single transaction path for explicit submission and automatic timeout. */
@Service
public class FinalizationService {
    private final AccessControl access;
    private final AttemptRepository attempts;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final AnswerRepository answers;
    private final GradingService grading;
    private final Clock clock;

    public FinalizationService(AccessControl access, AttemptRepository attempts,
                               QuestionRepository questions, QuestionOptionRepository options,
                               AnswerRepository answers, GradingService grading, Clock clock) {
        this.access = access;
        this.attempts = attempts;
        this.questions = questions;
        this.options = options;
        this.answers = answers;
        this.grading = grading;
        this.clock = clock;
    }

    @Transactional
    public StudentResultView submit(long attemptId) {
        Attempt attempt = access.ownAttemptForUpdate(attemptId);
        if (attempt.status() != AttemptStatus.IN_PROGRESS) {
            return StudentResultView.from(attempt);
        }
        Instant now = now();
        AttemptStatus terminal = now.isBefore(attempt.deadlineAt())
                ? AttemptStatus.SUBMITTED : AttemptStatus.TIMED_OUT;
        return finalizeLocked(attempt, now, terminal);
    }

    /** Scheduler entry point: each call rechecks the deadline under the same row lock as saves. */
    @Transactional
    public Optional<StudentResultView> finalizeIfExpired(long attemptId) {
        Attempt attempt = attempts.findByIdForUpdate(attemptId).orElse(null);
        if (attempt == null) return Optional.empty();
        if (attempt.status() != AttemptStatus.IN_PROGRESS) {
            return Optional.of(StudentResultView.from(attempt));
        }
        Instant now = now();
        if (now.isBefore(attempt.deadlineAt())) return Optional.empty();
        return Optional.of(finalizeLocked(attempt, now, AttemptStatus.TIMED_OUT));
    }

    private StudentResultView finalizeLocked(Attempt attempt, Instant now, AttemptStatus terminal) {
        List<Question> paper = questions.findByExam(attempt.examId());
        Map<Long, List<QuestionOption>> keys = options.findByExam(attempt.examId());
        List<Answer> saved = answers.findByAttempt(attempt.id());
        GradingService.Grade grade = grading.grade(paper, keys, saved);
        if (attempts.finalizeAttempt(attempt.id(), terminal, now,
                grade.score(), grade.totalMarks()) != 1) {
            throw new IllegalStateException("Attempt finalization changed concurrently");
        }
        return StudentResultView.from(attempts.findById(attempt.id()).orElseThrow());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}

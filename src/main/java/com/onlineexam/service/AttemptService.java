package com.onlineexam.service;

import com.onlineexam.domain.Answer;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.dto.QuestionViews.StudentOption;
import com.onlineexam.dto.StudentAttemptView;
import com.onlineexam.dto.StudentAttemptView.QuestionState;
import com.onlineexam.dto.StudentAttemptSummary;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.AttemptRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class AttemptService {
    private final AccessControl access;
    private final AttemptStartTransaction starts;
    private final AttemptRepository attempts;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final AnswerRepository answers;
    private final FinalizationService finalization;
    private final Clock clock;

    public AttemptService(AccessControl access, AttemptStartTransaction starts,
                          AttemptRepository attempts,
                          QuestionRepository questions, QuestionOptionRepository options,
                          AnswerRepository answers, FinalizationService finalization, Clock clock) {
        this.access = access;
        this.starts = starts;
        this.attempts = attempts;
        this.questions = questions;
        this.options = options;
        this.answers = answers;
        this.finalization = finalization;
        this.clock = clock;
    }

    public StudentAttemptView start(long examId) {
        long studentId = access.student().id();
        Attempt attempt;
        try {
            attempt = starts.startOrResume(examId, studentId);
        } catch (DuplicateKeyException ignored) {
            attempt = starts.findWinner(examId, studentId);
        }
        return read(attempt.id());
    }

    public List<StudentAttemptSummary> listMine() {
        long studentId = access.student().id();
        for (Attempt attempt : attempts.findByStudent(studentId)) {
            if (expired(attempt)) finalization.finalizeIfExpired(attempt.id());
        }
        return attempts.findByStudent(studentId).stream()
                .map(StudentAttemptSummary::from).toList();
    }

    public StudentAttemptView read(long attemptId) {
        Attempt attempt = access.ownAttempt(attemptId);
        if (expired(attempt)) {
            finalization.finalizeIfExpired(attemptId);
            attempt = access.ownAttempt(attemptId);
        }
        Map<Long, Answer> saved = answers.findByAttempt(attemptId).stream()
                .collect(Collectors.toMap(Answer::questionId, Function.identity()));
        Map<Long, List<QuestionOption>> choices = options.findByExam(attempt.examId());
        List<QuestionState> paper = questions.findByExam(attempt.examId()).stream()
                .map(question -> state(question, choices.getOrDefault(question.id(), List.of()),
                        saved.get(question.id())))
                .toList();
        return StudentAttemptView.from(attempt, clock.instant(), paper);
    }

    private boolean expired(Attempt attempt) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return attempt.status() == AttemptStatus.IN_PROGRESS
                && !now.isBefore(attempt.deadlineAt());
    }

    private QuestionState state(Question question, List<QuestionOption> choices, Answer saved) {
        List<StudentOption> view = choices.stream()
                .map(option -> new StudentOption(option.id(), option.text(), option.displayOrder()))
                .toList();
        return new QuestionState(question.id(), question.text(), question.marks(),
                question.displayOrder(), view,
                saved == null ? null : saved.selectedOptionId(),
                saved == null ? 0 : saved.revision());
    }
}

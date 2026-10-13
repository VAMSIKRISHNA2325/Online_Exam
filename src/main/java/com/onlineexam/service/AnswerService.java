package com.onlineexam.service;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Answer;
import com.onlineexam.domain.AnswerRevision;
import com.onlineexam.domain.Attempt;
import com.onlineexam.domain.AttemptStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.dto.AnswerSaveRequest;
import com.onlineexam.dto.StudentAnswerView;
import com.onlineexam.repository.AnswerRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnswerService {
    private final AccessControl access;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final AnswerRepository answers;
    private final Clock clock;

    public AnswerService(AccessControl access, QuestionRepository questions,
                         QuestionOptionRepository options, AnswerRepository answers,
                         Clock clock) {
        this.access = access;
        this.questions = questions;
        this.options = options;
        this.answers = answers;
        this.clock = clock;
    }

    @Transactional
    public StudentAnswerView save(long attemptId, long questionId, AnswerSaveRequest request) {
        // Finalization must take this same lock before checking state or reading answers.
        Attempt attempt = access.ownAttemptForUpdate(attemptId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (attempt.status() != AttemptStatus.IN_PROGRESS) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_FINALIZED",
                    "This attempt is already finalized.");
        }
        if (!now.isBefore(attempt.deadlineAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_EXPIRED",
                    "The answer deadline has passed.");
        }
        Question question = questions.findById(questionId)
                .filter(found -> found.examId() == attempt.examId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Question was not found."));
        if (request.selectedOptionId() != null) {
            QuestionOption selected = options.findById(request.selectedOptionId()).orElse(null);
            if (selected == null || selected.questionId() != question.id()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OPTION",
                        "Choose an option from this question.");
            }
        }

        Answer current = answers.find(attemptId, questionId).orElse(null);
        int revision = current == null ? 0 : current.revision();
        Long selection = current == null ? null : current.selectedOptionId();
        if (current != null && Objects.equals(request.selectedOptionId(), selection)) {
            return StudentAnswerView.from(current);
        }
        if (request.expectedRevision() != revision) {
            throw new AnswerRevisionConflict(current == null
                    ? StudentAnswerView.unsaved(questionId) : StudentAnswerView.from(current));
        }
        if (current == null && request.selectedOptionId() == null) {
            return StudentAnswerView.unsaved(questionId);
        }

        int next = Math.addExact(revision, 1);
        Answer changed = new Answer(attemptId, attempt.examId(), questionId,
                request.selectedOptionId(), next, now);
        if (current == null) {
            answers.create(changed);
        } else if (answers.updateIfRevision(changed, revision) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "ANSWER_CONFLICT",
                    "The answer changed. Reload the attempt before retrying.");
        }
        answers.appendRevision(new AnswerRevision(attemptId, questionId, next,
                request.selectedOptionId(), now));
        return StudentAnswerView.from(changed);
    }
}

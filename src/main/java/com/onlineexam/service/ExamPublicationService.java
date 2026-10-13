package com.onlineexam.service;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.dto.AuthoringViews.ExamView;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamPublicationService {
    private final AccessControl access;
    private final ExamRepository exams;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final Clock clock;

    public ExamPublicationService(AccessControl access, ExamRepository exams,
                                  QuestionRepository questions, QuestionOptionRepository options,
                                  Clock clock) {
        this.access = access;
        this.exams = exams;
        this.questions = questions;
        this.options = options;
        this.clock = clock;
    }

    /** The exam row serializes publication with every draft edit. */
    @Transactional
    public ExamView publish(long examId) {
        Exam exam = access.ownedExamForUpdate(examId);
        if (exam.status() != ExamStatus.DRAFT) {
            throw conflict("EXAM_PUBLISHED", "This exam is already published.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        validatePaper(exam, now);
        Exam published = new Exam(exam.id(), exam.instructorId(), exam.title(),
                exam.description(), exam.durationMinutes(), exam.opensAt(), exam.closesAt(),
                ExamStatus.PUBLISHED, now);
        if (exams.update(published) != 1) {
            throw conflict("PUBLICATION_CONFLICT", "The exam could not be published.");
        }
        return ExamView.from(exams.findById(examId).orElseThrow());
    }

    private void validatePaper(Exam exam, Instant now) {
        if (exam.title() == null || exam.title().isBlank() || exam.durationMinutes() <= 0
                || exam.opensAt() == null || exam.closesAt() == null
                || !exam.closesAt().isAfter(exam.opensAt())
                || !exam.closesAt().isAfter(now)) {
            throw invalid("Set a title, positive duration, and an availability window that has not closed.");
        }
        List<Question> paper = questions.findByExam(exam.id());
        if (paper.isEmpty()) throw invalid("Add at least one valid question before publishing.");
        Map<Long, List<QuestionOption>> choices = options.findByExam(exam.id());
        Set<Integer> orders = new HashSet<>();
        for (Question question : paper) {
            if (question.text() == null || question.text().isBlank()
                    || question.marks() <= 0 || question.displayOrder() < 0
                    || !orders.add(question.displayOrder())) {
                throw invalid("Every question needs text, positive marks, and a unique display order.");
            }
            List<QuestionOption> answers = choices.getOrDefault(question.id(), List.of());
            if (answers.size() < 2 || answers.size() > 6) {
                throw invalid("Each question needs two to six options.");
            }
            int correct = 0;
            Set<Integer> optionOrders = new HashSet<>();
            for (QuestionOption option : answers) {
                if (option.text() == null || option.text().isBlank()
                        || option.displayOrder() < 0 || !optionOrders.add(option.displayOrder())) {
                    throw invalid("Every option needs text and a unique display order.");
                }
                if (option.correct()) correct++;
            }
            if (correct != 1) throw invalid("Each question needs exactly one correct option.");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_PAPER", message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}

package com.onlineexam.service;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import com.onlineexam.dto.AuthoringRequests.ExamRequest;
import com.onlineexam.dto.AuthoringRequests.OptionRequest;
import com.onlineexam.dto.AuthoringRequests.QuestionRequest;
import com.onlineexam.dto.AuthoringViews.ExamDetail;
import com.onlineexam.dto.AuthoringViews.ExamView;
import com.onlineexam.dto.QuestionViews.InstructorQuestion;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionOptionRepository;
import com.onlineexam.repository.QuestionRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamAuthoringService {
    private final AccessControl access;
    private final ExamRepository exams;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;

    public ExamAuthoringService(AccessControl access, ExamRepository exams,
                                QuestionRepository questions, QuestionOptionRepository options) {
        this.access = access;
        this.exams = exams;
        this.questions = questions;
        this.options = options;
    }

    @Transactional(readOnly = true)
    public List<ExamView> listMine() {
        return exams.findByInstructor(access.instructor().id()).stream().map(ExamView::from).toList();
    }

    @Transactional(readOnly = true)
    public ExamDetail detail(long examId) {
        Exam exam = access.ownedExam(examId);
        Map<Long, List<QuestionOption>> choices = options.findByExam(examId);
        List<InstructorQuestion> paper = questions.findByExam(examId).stream()
                .map(question -> InstructorQuestion.from(question,
                        choices.getOrDefault(question.id(), List.of())))
                .toList();
        return new ExamDetail(ExamView.from(exam), paper);
    }

    @Transactional
    public ExamView createExam(ExamRequest request) {
        validateExam(request);
        long instructorId = access.instructor().id();
        long id = exams.create(exam(0, instructorId, request));
        return ExamView.from(exams.findById(id).orElseThrow());
    }

    @Transactional
    public ExamView updateExam(long examId, ExamRequest request) {
        validateExam(request);
        Exam current = draftForUpdate(examId);
        exams.update(exam(current.id(), current.instructorId(), request));
        return ExamView.from(exams.findById(examId).orElseThrow());
    }

    @Transactional
    public void deleteExam(long examId) {
        draftForUpdate(examId);
        try {
            exams.deleteById(examId);
        } catch (DataIntegrityViolationException ignored) {
            throw conflict("CONTENT_IN_USE", "This exam has related records and cannot be deleted.");
        }
    }

    @Transactional
    public InstructorQuestion createQuestion(long examId, QuestionRequest request) {
        validateQuestion(request);
        draftForUpdate(examId);
        try {
            long id = questions.create(new Question(0, examId, request.text().trim(),
                    request.marks(), request.displayOrder()));
            insertOptions(id, request.options());
            return questionView(id);
        } catch (DuplicateKeyException ignored) {
            throw conflict("QUESTION_ORDER_CONFLICT", "A question already uses this display order.");
        }
    }

    @Transactional
    public InstructorQuestion updateQuestion(long questionId, QuestionRequest request) {
        validateQuestion(request);
        Question current = lockedDraftQuestion(questionId);
        try {
            questions.update(new Question(questionId, current.examId(), request.text().trim(),
                    request.marks(), request.displayOrder()));
            for (QuestionOption existing : options.findByQuestion(questionId)) {
                options.deleteById(existing.id());
            }
            insertOptions(questionId, request.options());
            return questionView(questionId);
        } catch (DuplicateKeyException ignored) {
            throw conflict("QUESTION_ORDER_CONFLICT", "A question already uses this display order.");
        } catch (DataIntegrityViolationException ignored) {
            throw conflict("CONTENT_IN_USE", "This question has related records and cannot be changed.");
        }
    }

    @Transactional
    public void deleteQuestion(long questionId) {
        lockedDraftQuestion(questionId);
        try {
            questions.deleteById(questionId);
        } catch (DataIntegrityViolationException ignored) {
            throw conflict("CONTENT_IN_USE", "This question has related records and cannot be deleted.");
        }
    }

    private Question lockedDraftQuestion(long questionId) {
        Question snapshot = questions.findById(questionId).orElseThrow(ExamAuthoringService::missingQuestion);
        draftForUpdate(snapshot.examId());
        Question current = questions.findById(questionId).orElseThrow(ExamAuthoringService::missingQuestion);
        if (current.examId() != snapshot.examId()) throw missingQuestion();
        return current;
    }

    private Exam draftForUpdate(long examId) {
        Exam exam = access.ownedExamForUpdate(examId);
        if (exam.status() != ExamStatus.DRAFT) {
            throw conflict("EXAM_PUBLISHED", "Published exam content cannot be changed.");
        }
        return exam;
    }

    private InstructorQuestion questionView(long questionId) {
        Question question = questions.findById(questionId).orElseThrow();
        return InstructorQuestion.from(question, options.findByQuestion(questionId));
    }

    private void insertOptions(long questionId, List<OptionRequest> entries) {
        for (OptionRequest entry : entries) {
            options.create(new QuestionOption(0, questionId, entry.text().trim(),
                    entry.correct(), entry.displayOrder()));
        }
    }

    private static Exam exam(long id, long instructorId, ExamRequest request) {
        return new Exam(id, instructorId, request.title().trim(),
                request.description() == null ? "" : request.description().trim(),
                request.durationMinutes(), request.opensAt(), request.closesAt(),
                ExamStatus.DRAFT, null);
    }

    private static void validateExam(ExamRequest request) {
        if (!request.closesAt().isAfter(request.opensAt())) {
            throw bad("The closing time must be after the opening time.");
        }
    }

    private static void validateQuestion(QuestionRequest request) {
        int correct = 0;
        Set<Integer> orders = new HashSet<>();
        for (OptionRequest option : request.options()) {
            if (Boolean.TRUE.equals(option.correct())) correct++;
            if (!orders.add(option.displayOrder())) {
                throw bad("Each option must have a unique display order.");
            }
        }
        if (correct != 1) throw bad("Select exactly one correct option.");
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException missingQuestion() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Question was not found.");
    }
}

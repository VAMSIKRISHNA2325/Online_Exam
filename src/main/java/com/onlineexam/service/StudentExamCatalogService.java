package com.onlineexam.service;

import com.onlineexam.controller.ApiException;
import com.onlineexam.domain.Exam;
import com.onlineexam.domain.ExamStatus;
import com.onlineexam.dto.StudentExamView;
import com.onlineexam.repository.ExamRepository;
import com.onlineexam.repository.QuestionRepository;
import com.onlineexam.repository.QuestionRepository.Stats;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentExamCatalogService {
    private final AccessControl access;
    private final ExamRepository exams;
    private final QuestionRepository questions;
    private final ExamAvailabilityPolicy availability;
    private final Clock clock;

    public StudentExamCatalogService(AccessControl access, ExamRepository exams,
                                     QuestionRepository questions,
                                     ExamAvailabilityPolicy availability, Clock clock) {
        this.access = access;
        this.exams = exams;
        this.questions = questions;
        this.availability = availability;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StudentExamView> list() {
        access.student();
        Instant now = clock.instant();
        Map<Long, Stats> stats = questions.statsByExam();
        return exams.findPublished().stream().map(exam -> view(exam, now,
                stats.getOrDefault(exam.id(), new Stats(0, 0)))).toList();
    }

    @Transactional(readOnly = true)
    public StudentExamView detail(long examId) {
        access.student();
        Exam exam = exams.findById(examId)
                .filter(found -> found.status() == ExamStatus.PUBLISHED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Exam was not found."));
        return view(exam, clock.instant(), questions.stats(examId));
    }

    private StudentExamView view(Exam exam, Instant now, Stats totals) {
        return StudentExamView.from(exam, availability.state(exam, now),
                totals.count(), totals.marks());
    }
}

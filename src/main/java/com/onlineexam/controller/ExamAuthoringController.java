package com.onlineexam.controller;

import com.onlineexam.dto.AuthoringRequests.ExamRequest;
import com.onlineexam.dto.AuthoringRequests.QuestionRequest;
import com.onlineexam.dto.AuthoringViews.ExamDetail;
import com.onlineexam.dto.AuthoringViews.ExamView;
import com.onlineexam.dto.QuestionViews.InstructorQuestion;
import com.onlineexam.service.ExamAuthoringService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExamAuthoringController {
    private final ExamAuthoringService authoring;

    public ExamAuthoringController(ExamAuthoringService authoring) {
        this.authoring = authoring;
    }

    @GetMapping("/api/instructor/exams")
    public List<ExamView> listMine() {
        return authoring.listMine();
    }

    @GetMapping("/api/instructor/exams/{examId}")
    public ExamDetail detail(@PathVariable long examId) {
        return authoring.detail(examId);
    }

    @PostMapping("/api/exams")
    public ResponseEntity<ExamView> createExam(@Valid @RequestBody ExamRequest request) {
        ExamView created = authoring.createExam(request);
        return ResponseEntity.created(URI.create("/api/instructor/exams/" + created.id())).body(created);
    }

    @PutMapping("/api/exams/{examId}")
    public ExamView updateExam(@PathVariable long examId, @Valid @RequestBody ExamRequest request) {
        return authoring.updateExam(examId, request);
    }

    @DeleteMapping("/api/exams/{examId}")
    public ResponseEntity<Void> deleteExam(@PathVariable long examId) {
        authoring.deleteExam(examId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/exams/{examId}/questions")
    public ResponseEntity<InstructorQuestion> createQuestion(
            @PathVariable long examId, @Valid @RequestBody QuestionRequest request) {
        InstructorQuestion created = authoring.createQuestion(examId, request);
        return ResponseEntity.created(URI.create("/api/questions/" + created.id())).body(created);
    }

    @PutMapping("/api/questions/{questionId}")
    public InstructorQuestion updateQuestion(@PathVariable long questionId,
                                             @Valid @RequestBody QuestionRequest request) {
        return authoring.updateQuestion(questionId, request);
    }

    @DeleteMapping("/api/questions/{questionId}")
    public ResponseEntity<Void> deleteQuestion(@PathVariable long questionId) {
        authoring.deleteQuestion(questionId);
        return ResponseEntity.noContent().build();
    }
}

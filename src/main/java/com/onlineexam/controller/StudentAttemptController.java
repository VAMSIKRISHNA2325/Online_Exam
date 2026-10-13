package com.onlineexam.controller;

import com.onlineexam.dto.StudentAttemptView;
import com.onlineexam.dto.StudentAttemptSummary;
import com.onlineexam.service.AttemptService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StudentAttemptController {
    private final AttemptService attempts;

    public StudentAttemptController(AttemptService attempts) {
        this.attempts = attempts;
    }

    @PostMapping("/api/exams/{examId}/attempts")
    public StudentAttemptView start(@PathVariable long examId) {
        return attempts.start(examId);
    }

    @GetMapping("/api/attempts")
    public List<StudentAttemptSummary> listMine() {
        return attempts.listMine();
    }

    @GetMapping("/api/attempts/{attemptId}")
    public StudentAttemptView read(@PathVariable long attemptId) {
        return attempts.read(attemptId);
    }
}

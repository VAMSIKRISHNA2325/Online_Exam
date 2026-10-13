package com.onlineexam.controller;

import com.onlineexam.dto.StudentResultView;
import com.onlineexam.service.FinalizationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StudentSubmissionController {
    private final FinalizationService finalization;

    public StudentSubmissionController(FinalizationService finalization) {
        this.finalization = finalization;
    }

    @PostMapping("/api/attempts/{attemptId}/submit")
    public StudentResultView submit(@PathVariable long attemptId) {
        return finalization.submit(attemptId);
    }
}

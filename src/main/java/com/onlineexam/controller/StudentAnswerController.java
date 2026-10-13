package com.onlineexam.controller;

import com.onlineexam.dto.AnswerSaveRequest;
import com.onlineexam.dto.StudentAnswerView;
import com.onlineexam.service.AnswerRevisionConflict;
import com.onlineexam.service.AnswerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StudentAnswerController {
    private final AnswerService answers;

    public StudentAnswerController(AnswerService answers) {
        this.answers = answers;
    }

    @PutMapping("/api/attempts/{attemptId}/answers/{questionId}")
    public StudentAnswerView save(@PathVariable long attemptId, @PathVariable long questionId,
                                  @Valid @RequestBody AnswerSaveRequest request) {
        return answers.save(attemptId, questionId, request);
    }

    @ExceptionHandler(AnswerRevisionConflict.class)
    public ResponseEntity<StaleAnswerResponse> stale(AnswerRevisionConflict exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new StaleAnswerResponse(
                "STALE_REVISION", exception.getMessage(), exception.current()));
    }

    public record StaleAnswerResponse(String code, String message, StudentAnswerView current) {
    }
}

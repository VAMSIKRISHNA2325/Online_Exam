package com.onlineexam.controller;

import com.onlineexam.dto.ActiveAttemptView;
import com.onlineexam.service.SessionRecoveryService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionRecoveryController {
    private final SessionRecoveryService recovery;

    public SessionRecoveryController(SessionRecoveryService recovery) {
        this.recovery = recovery;
    }

    @GetMapping("/api/instructor/exams/{examId}/active-attempts")
    public List<ActiveAttemptView> activeAttempts(@PathVariable long examId) {
        return recovery.activeAttempts(examId);
    }

    @PostMapping("/api/attempts/{attemptId}/session-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recover(@PathVariable long attemptId) {
        recovery.recover(attemptId);
    }
}

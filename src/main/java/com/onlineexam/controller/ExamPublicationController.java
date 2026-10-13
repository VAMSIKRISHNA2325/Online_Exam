package com.onlineexam.controller;

import com.onlineexam.dto.AuthoringViews.ExamView;
import com.onlineexam.service.ExamPublicationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExamPublicationController {
    private final ExamPublicationService publication;

    public ExamPublicationController(ExamPublicationService publication) {
        this.publication = publication;
    }

    @PostMapping("/api/exams/{examId}/publish")
    public ExamView publish(@PathVariable long examId) {
        return publication.publish(examId);
    }
}

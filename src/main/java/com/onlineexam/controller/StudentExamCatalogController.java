package com.onlineexam.controller;

import com.onlineexam.dto.StudentExamView;
import com.onlineexam.service.StudentExamCatalogService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StudentExamCatalogController {
    private final StudentExamCatalogService catalog;

    public StudentExamCatalogController(StudentExamCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/exams")
    public List<StudentExamView> list() {
        return catalog.list();
    }

    @GetMapping("/api/exams/{examId}")
    public StudentExamView detail(@PathVariable long examId) {
        return catalog.detail(examId);
    }
}

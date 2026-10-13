package com.onlineexam.controller;

import com.onlineexam.dto.InstructorEventView;
import com.onlineexam.dto.InstructorResultView;
import com.onlineexam.dto.StudentResultView;
import com.onlineexam.service.ResultQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResultQueryController {
    private final ResultQueryService results;

    public ResultQueryController(ResultQueryService results) {
        this.results = results;
    }

    @GetMapping("/api/attempts/{attemptId}/result")
    public StudentResultView studentResult(@PathVariable long attemptId) {
        return results.studentResult(attemptId);
    }

    @GetMapping("/api/instructor/exams/{examId}/results")
    public List<InstructorResultView> instructorResults(@PathVariable long examId) {
        return results.instructorResults(examId);
    }

    @GetMapping("/api/instructor/exams/{examId}/events")
    public List<InstructorEventView> instructorEvents(@PathVariable long examId) {
        return results.instructorEvents(examId);
    }
}

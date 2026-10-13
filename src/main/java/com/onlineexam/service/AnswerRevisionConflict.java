package com.onlineexam.service;

import com.onlineexam.dto.StudentAnswerView;

public class AnswerRevisionConflict extends RuntimeException {
    private final StudentAnswerView current;

    public AnswerRevisionConflict(StudentAnswerView current) {
        super("The answer changed. Review the saved choice before retrying.");
        this.current = current;
    }

    public StudentAnswerView current() {
        return current;
    }
}

package com.onlineexam.service;

import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class McqEvaluator implements AnswerEvaluator {
    @Override
    public boolean isCorrect(Question question, List<QuestionOption> options, Long selectedOptionId) {
        if (options == null || options.size() < 2 || options.size() > 6) {
            throw new IllegalStateException("Published question has an invalid option count");
        }
        Set<Long> ids = new HashSet<>();
        long correctId = -1;
        for (QuestionOption option : options) {
            if (option.questionId() != question.id() || !ids.add(option.id())) {
                throw new IllegalStateException("Published question has invalid option relationships");
            }
            if (option.correct()) {
                if (correctId != -1) throw new IllegalStateException("Published question has multiple keys");
                correctId = option.id();
            }
        }
        if (correctId == -1) throw new IllegalStateException("Published question has no key");
        if (selectedOptionId == null) return false;
        if (!ids.contains(selectedOptionId)) {
            throw new IllegalStateException("Saved answer refers to an option outside its question");
        }
        return selectedOptionId == correctId;
    }
}

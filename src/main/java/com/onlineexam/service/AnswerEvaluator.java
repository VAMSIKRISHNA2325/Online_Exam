package com.onlineexam.service;

import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import java.util.List;

/** Server-side answer evaluation contract; never serialize this with student views. */
public interface AnswerEvaluator {
    boolean isCorrect(Question question, List<QuestionOption> options, Long selectedOptionId);
}

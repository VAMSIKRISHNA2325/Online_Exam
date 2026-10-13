package com.onlineexam.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionViewsTest {
    @Test
    void studentPaperNeverSerializesTheAnswerKey() throws Exception {
        Question question = new Question(9, 4, "Choose one answer", 5, 0);
        List<QuestionOption> options = List.of(
                new QuestionOption(21, 9, "First", false, 0),
                new QuestionOption(22, 9, "Second", true, 1));
        ObjectMapper json = new ObjectMapper();

        String student = json.writeValueAsString(QuestionViews.StudentQuestion.from(question, options));
        String instructor = json.writeValueAsString(QuestionViews.InstructorQuestion.from(question, options));

        assertThat(student).contains("Choose one answer", "First", "Second")
                .doesNotContain("correct", "is_correct", "password", "answerKey");
        assertThat(instructor).contains("\"correct\":true");
    }
}

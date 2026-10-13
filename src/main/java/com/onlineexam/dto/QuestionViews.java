package com.onlineexam.dto;

import com.onlineexam.domain.Question;
import com.onlineexam.domain.QuestionOption;
import java.util.List;

/** Separate shapes make it impossible to serialize a correct-answer flag in a student paper. */
public final class QuestionViews {
    private QuestionViews() {
    }

    public record StudentOption(long id, String text, int displayOrder) {
    }

    public record StudentQuestion(long id, String text, int marks, int displayOrder,
                                  List<StudentOption> options) {
        public static StudentQuestion from(Question question, List<QuestionOption> options) {
            return new StudentQuestion(question.id(), question.text(), question.marks(),
                    question.displayOrder(), options.stream()
                    .map(option -> new StudentOption(option.id(), option.text(), option.displayOrder()))
                    .toList());
        }
    }

    public record InstructorOption(long id, String text, boolean correct, int displayOrder) {
    }

    public record InstructorQuestion(long id, String text, int marks, int displayOrder,
                                     List<InstructorOption> options) {
        public static InstructorQuestion from(Question question, List<QuestionOption> options) {
            return new InstructorQuestion(question.id(), question.text(), question.marks(),
                    question.displayOrder(), options.stream()
                    .map(option -> new InstructorOption(option.id(), option.text(),
                            option.correct(), option.displayOrder()))
                    .toList());
        }
    }
}

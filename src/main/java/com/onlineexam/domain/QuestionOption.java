package com.onlineexam.domain;

/** The answer key stays in server-side code; student responses must use a separate DTO. */
public record QuestionOption(long id, long questionId, String text, boolean correct, int displayOrder) {
}

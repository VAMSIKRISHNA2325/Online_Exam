package com.onlineexam.domain;

public record Question(long id, long examId, String text, int marks, int displayOrder) {
}

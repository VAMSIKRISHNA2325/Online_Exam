package com.onlineexam.dto;

/** A student's terminal aggregate, with only the identity needed for an instructor roster. */
public record InstructorResultView(long studentId, String username, String displayName,
                                   StudentResultView result) {
}

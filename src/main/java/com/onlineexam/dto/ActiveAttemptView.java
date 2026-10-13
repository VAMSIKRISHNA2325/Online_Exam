package com.onlineexam.dto;

import java.time.Instant;

/** Minimal instructor roster entry for supervised recovery of a current attempt. */
public record ActiveAttemptView(long attemptId, long studentId, String username,
                                String displayName, Instant deadlineAt) {
}

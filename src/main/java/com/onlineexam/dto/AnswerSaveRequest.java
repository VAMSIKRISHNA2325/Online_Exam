package com.onlineexam.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AnswerSaveRequest(@Positive Long selectedOptionId,
                                @NotNull @Min(0) Integer expectedRevision) {
}

package com.onlineexam.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AuthoringRequests {
    private AuthoringRequests() {
    }

    public record ExamRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String description,
            @NotNull @Min(1) @Max(10080) Integer durationMinutes,
            @NotNull Instant opensAt,
            @NotNull Instant closesAt) {
    }

    public record QuestionRequest(
            @NotBlank @Size(max = 5000) String text,
            @NotNull @Min(1) @Max(1000) Integer marks,
            @NotNull @Min(0) Integer displayOrder,
            @NotNull @Size(min = 2, max = 6) List<@NotNull @Valid OptionRequest> options) {
    }

    public record OptionRequest(
            @NotBlank @Size(max = 5000) String text,
            @NotNull Boolean correct,
            @NotNull @Min(0) Integer displayOrder) {
    }
}

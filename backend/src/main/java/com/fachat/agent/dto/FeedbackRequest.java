package com.fachat.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record FeedbackRequest(
    @NotNull FeedbackType type,
    @NotBlank String responseContent,
    @NotBlank String userPrompt,
    String conversationId,
    String plannerModelId,
    String executorModelId,
    String reviewerModelId,
    @Size(max = 800) String reason,
    List<String> selectedModelIds
) {
}

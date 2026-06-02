package com.fachat.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConversationTitleUpdateRequest(
    @NotBlank
    @Size(max = 64)
    String title
) {
}

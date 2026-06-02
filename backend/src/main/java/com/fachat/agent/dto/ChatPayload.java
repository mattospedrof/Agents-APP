package com.fachat.agent.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ChatPayload(
    String conversationId,
    @NotEmpty List<ChatMessage> messages,
    List<String> selectedModelIds,
    ReasoningMode reasoningMode,
    ModelSelectionPayload modelSelection,
    String activeFileContextId
) {
}

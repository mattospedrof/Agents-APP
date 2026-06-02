package com.fachat.agent.dto;

public record ModelSelectionPayload(
    String plannerModelId,
    String executorModelId,
    String reviewerModelId
) {
}

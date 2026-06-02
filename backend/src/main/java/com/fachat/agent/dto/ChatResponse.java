package com.fachat.agent.dto;

public record ChatResponse(
    String response,
    String conversationId,
    String version,
    String conversationTitle,
    String plannerModelId,
    String executorModelId,
    String reviewerModelId,
    AssistantDocument document,
    ActiveFileResponse activeFile,
    ResponseRenderMeta renderMeta
) {
}

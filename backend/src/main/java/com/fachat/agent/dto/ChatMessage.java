package com.fachat.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(
    String role,
    String content,
    String attachmentName,
    String attachmentSummary,
    AssistantDocument document,
    String fileContextId
) {
    public ChatMessage(String role, String content, String attachmentName, String attachmentSummary) {
        this(role, content, attachmentName, attachmentSummary, null, null);
    }

    public ChatMessage(String role, String content, String attachmentName, String attachmentSummary, AssistantDocument document) {
        this(role, content, attachmentName, attachmentSummary, document, null);
    }

    public ChatMessage withAttachment(String name, String summary) {
        return new ChatMessage(role, content, name, summary, document, fileContextId);
    }

    public ChatMessage withDocument(AssistantDocument document) {
        return new ChatMessage(role, content, attachmentName, attachmentSummary, document, fileContextId);
    }

    public ChatMessage withFileContextId(String nextFileContextId) {
        return new ChatMessage(role, content, attachmentName, attachmentSummary, document, nextFileContextId);
    }
}

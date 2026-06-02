package com.fachat.agent.dto;

import java.time.Instant;
import java.util.List;

public record ConversationDetailResponse(
    String id,
    String title,
    List<ChatMessage> messages,
    Instant updatedAt,
    ActiveFileResponse activeFile
) {
}

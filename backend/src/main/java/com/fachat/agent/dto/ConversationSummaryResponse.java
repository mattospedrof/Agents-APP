package com.fachat.agent.dto;

import java.time.Instant;

public record ConversationSummaryResponse(
    String id,
    String title,
    String preview,
    Instant updatedAt
) {
}

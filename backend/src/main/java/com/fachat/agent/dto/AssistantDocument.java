package com.fachat.agent.dto;

import java.util.List;

public record AssistantDocument(
    int version,
    List<AssistantBlock> blocks
) {
    public static AssistantDocument of(List<AssistantBlock> blocks) {
        return new AssistantDocument(1, List.copyOf(blocks));
    }
}

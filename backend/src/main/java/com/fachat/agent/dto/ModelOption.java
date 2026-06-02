package com.fachat.agent.dto;

import java.util.List;

public record ModelOption(
    String id,
    String label,
    String description,
    boolean supportsFiles,
    List<String> recommendedFor
) {
}

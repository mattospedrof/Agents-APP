package com.fachat.agent.dto;

import java.util.List;

public record ReviewerOutput(
    boolean approved,
    List<String> issues_found,
    String final_response
) {
    // Fallback: se o reviewer falhar, aprova o draft direto
    public static ReviewerOutput fallback(String draft) {
        return new ReviewerOutput(true, List.of(), draft);
    }
}

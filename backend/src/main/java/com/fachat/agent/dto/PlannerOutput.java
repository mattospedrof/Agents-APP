package com.fachat.agent.dto;

import java.util.List;

public record PlannerOutput(
    String intent,
    String conversation_title,
    String task_type,
    String complexity,       // low | medium | high
    String executor,         // CODE_EXECUTOR | GENERAL_EXECUTOR | RESEARCH_EXECUTOR
    boolean needs_tools,
    String response_style,
    List<String> execution_plan
) {
    // Fallback seguro quando o planner falha em retornar JSON válido
    public static PlannerOutput fallback(String intent) {
        return new PlannerOutput(
            intent, null, "general", "medium",
            "GENERAL_EXECUTOR", false,
            "clear and direct", List.of("Respond helpfully to the user")
        );
    }
}

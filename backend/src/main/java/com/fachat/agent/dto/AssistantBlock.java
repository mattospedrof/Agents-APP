package com.fachat.agent.dto;

import java.util.List;

public record AssistantBlock(
    String type,
    String text,
    Integer level,
    List<String> items,
    String language,
    String code,
    List<String> columns,
    List<List<String>> rows
) {
    public static AssistantBlock paragraph(String text) {
        return new AssistantBlock("paragraph", text, null, null, null, null, null, null);
    }

    public static AssistantBlock heading(int level, String text) {
        return new AssistantBlock("heading", text, Math.max(2, Math.min(level, 3)), null, null, null, null, null);
    }

    public static AssistantBlock list(String type, List<String> items) {
        return new AssistantBlock(type, null, null, List.copyOf(items), null, null, null, null);
    }

    public static AssistantBlock codeBlock(String language, String code) {
        return new AssistantBlock("codeBlock", null, null, null, language, code, null, null);
    }

    public static AssistantBlock table(List<String> columns, List<List<String>> rows) {
        return new AssistantBlock("table", null, null, null, null, null, List.copyOf(columns), List.copyOf(rows));
    }

    public static AssistantBlock quote(String text) {
        return new AssistantBlock("blockquote", text, null, null, null, null, null, null);
    }

    public static AssistantBlock horizontalRule() {
        return new AssistantBlock("horizontalRule", null, null, null, null, null, null, null);
    }
}

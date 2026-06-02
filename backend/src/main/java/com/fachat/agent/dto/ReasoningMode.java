package com.fachat.agent.dto;

public enum ReasoningMode {
    FAST,
    THOUGHTFUL;

    public boolean isThoughtful() {
        return this == THOUGHTFUL;
    }
}

package com.fachat.agent.dto;

public record ResponseRenderMeta(
    long documentMs,
    long repairMs,
    long saveMs,
    boolean cached
) {
}

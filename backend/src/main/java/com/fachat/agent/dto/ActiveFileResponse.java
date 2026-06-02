package com.fachat.agent.dto;

public record ActiveFileResponse(
    String id,
    String fileName,
    String contentType,
    long sizeBytes
) {
}

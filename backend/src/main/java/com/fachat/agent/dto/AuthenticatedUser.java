package com.fachat.agent.dto;

public record AuthenticatedUser(
    String id,
    String name,
    String email
) {
}

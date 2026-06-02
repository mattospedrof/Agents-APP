package com.fachat.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.Map;

public final class SafeErrorResponseWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SafeErrorResponseWriter() {
    }

    public static void write(
        HttpServletResponse response,
        int status,
        String error,
        String requestId
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            MAPPER.writeValueAsString(Map.of(
                "error", error,
                "requestId", requestId == null ? "" : requestId
            ))
        );
    }
}

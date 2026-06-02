package com.fachat.agent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 8)
public class RestrictedEndpointFilter extends OncePerRequestFilter {

    private static final List<String> BLOCKED_PREFIXES = List.of(
        "/actuator/env",
        "/actuator/configprops",
        "/actuator/beans",
        "/actuator/heapdump",
        "/actuator/threaddump",
        "/actuator/loggers",
        "/swagger-ui",
        "/v3/api-docs",
        "/api-docs",
        "/config",
        "/env",
        "/debug",
        "/admin",
        "/internal"
    );

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (matchesBlockedPrefix(path)) {
            SafeErrorResponseWriter.write(
                response,
                HttpStatus.NOT_FOUND.value(),
                "Not found",
                requestId(request)
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matchesBlockedPrefix(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return BLOCKED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestContextKeys.REQUEST_ID);
        return value == null ? "" : String.valueOf(value);
    }
}

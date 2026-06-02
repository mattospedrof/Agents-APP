package com.fachat.agent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fachat.agent.config.AgentProperties;

import java.io.IOException;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RequestAuditLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RequestAuditLoggingFilter.class);
    private final AgentProperties properties;
    private final String serviceName;

    public RequestAuditLoggingFilter(
        AgentProperties properties,
        @Value("${spring.application.name:chat-backend}") String serviceName
    ) {
        this.properties = properties;
        this.serviceName = serviceName;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            String requestId = requestId(request);
            String userId = request.getHeader("X-Session-User-Id");
            if (userId == null || userId.isBlank()) {
                userId = "guest";
            }
            String maskedIp = maskIp(request.getRemoteAddr());
            String origin = request.getHeader("Origin");
            String originValue = origin == null || origin.isBlank() ? "-" : origin;
            String rateLimited = response.getStatus() == 429 ? "blocked" : "ok";

            logger.info(
                "{\"event\":\"http_request\",\"service\":\"{}\",\"environment\":\"{}\",\"requestId\":\"{}\",\"method\":\"{}\",\"path\":\"{}\",\"statusCode\":{},\"durationMs\":{},\"userId\":\"{}\",\"remoteIp\":\"{}\",\"origin\":\"{}\",\"rateLimitAction\":\"{}\"}",
                serviceName,
                properties.getEnvironment(),
                requestId,
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                durationMs,
                userId,
                maskedIp,
                originValue,
                rateLimited
            );
        }
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestContextKeys.REQUEST_ID);
        return value == null ? "" : String.valueOf(value);
    }

    private String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        int separator = ip.indexOf('.');
        if (separator == -1) {
            return ip.length() <= 4 ? "x" : ip.substring(0, 4) + "x";
        }
        return ip.substring(0, separator + 1) + "x.x.x";
    }
}

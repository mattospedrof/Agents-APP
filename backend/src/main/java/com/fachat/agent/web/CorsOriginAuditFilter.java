package com.fachat.agent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fachat.agent.config.AgentProperties;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class CorsOriginAuditFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(CorsOriginAuditFilter.class);
    private final Set<String> normalizedAllowedOrigins;

    public CorsOriginAuditFilter(AgentProperties properties) {
        this.normalizedAllowedOrigins = properties.getCorsAllowedOrigins().stream()
            .filter(origin -> origin != null && !origin.isBlank())
            .map(this::normalizeOrigin)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        String origin = request.getHeader("Origin");
        if (ApiRoutePolicy.isApiPath(path)
            && origin != null
            && !origin.isBlank()
            && !isAllowed(origin)) {
            String requestId = requestId(request);
            logger.warn(
                "{\"event\":\"cors_origin_rejected\",\"requestId\":\"{}\",\"path\":\"{}\",\"origin\":\"{}\"}",
                requestId,
                path,
                origin
            );
            SafeErrorResponseWriter.write(
                response,
                HttpStatus.FORBIDDEN.value(),
                "Origin not allowed",
                requestId
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowed(String origin) {
        String normalized = normalizeOrigin(origin);
        return normalizedAllowedOrigins.contains(normalized);
    }

    private String normalizeOrigin(String origin) {
        return origin.trim().toLowerCase(Locale.ROOT);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestContextKeys.REQUEST_ID);
        return value == null ? "" : String.valueOf(value);
    }
}

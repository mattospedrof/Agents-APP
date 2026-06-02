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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(InternalApiKeyFilter.class);
    private final AgentProperties properties;

    public InternalApiKeyFilter(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!ApiRoutePolicy.isSensitivePath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String configuredSecret = properties.getInternalApiSecret();
        if (configuredSecret == null || configuredSecret.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String received = request.getHeader(RequestContextKeys.INTERNAL_API_HEADER);
        if (!constantTimeEquals(configuredSecret, received)) {
            String requestId = requestId(request);
            logger.warn(
                "{\"event\":\"internal_key_rejected\",\"requestId\":\"{}\",\"path\":\"{}\",\"remoteIp\":\"{}\"}",
                requestId,
                path,
                maskIp(request.getRemoteAddr())
            );
            SafeErrorResponseWriter.write(
                response,
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                requestId
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String expected, String provided) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
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

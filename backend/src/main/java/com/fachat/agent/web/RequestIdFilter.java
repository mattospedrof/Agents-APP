package com.fachat.agent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_HEADER = Pattern.compile("^[a-zA-Z0-9._-]{8,80}$");

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String incoming = request.getHeader(RequestContextKeys.REQUEST_ID_HEADER);
        String requestId = isSafe(incoming) ? incoming : UUID.randomUUID().toString();

        request.setAttribute(RequestContextKeys.REQUEST_ID, requestId);
        request.setAttribute(RequestContextKeys.REQUEST_START_NANOS, System.nanoTime());
        response.setHeader(RequestContextKeys.REQUEST_ID_HEADER, requestId);
        MDC.put(RequestContextKeys.REQUEST_ID, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContextKeys.REQUEST_ID);
        }
    }

    private boolean isSafe(String value) {
        return value != null && SAFE_HEADER.matcher(value).matches();
    }
}

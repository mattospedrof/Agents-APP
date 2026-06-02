package com.fachat.agent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fachat.agent.config.AgentProperties;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class RequestBodySizeFilter extends OncePerRequestFilter {

    private final AgentProperties properties;

    public RequestBodySizeFilter(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        long maxBytes = properties.getMaxRequestBodyBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBytes) {
            SafeErrorResponseWriter.write(
                response,
                413,
                "Request body too large",
                requestId(request)
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestContextKeys.REQUEST_ID);
        return value == null ? "" : String.valueOf(value);
    }
}

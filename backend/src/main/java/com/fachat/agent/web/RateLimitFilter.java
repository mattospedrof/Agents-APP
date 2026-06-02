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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitFilter.class);
    private final AgentProperties properties;
    private final ConcurrentMap<String, BucketState> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!properties.isRateLimitEnabled() || !ApiRoutePolicy.isApiPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = normalizeIp(request.getRemoteAddr());
        String userId = normalizeUserId(request.getHeader("X-Session-User-Id"));
        long nowMillis = Instant.now().toEpochMilli();
        int blockSeconds = Math.max(1, properties.getRateLimitBlockSeconds());

        List<Decision> decisions = new ArrayList<>();
        decisions.add(checkAndConsume(
            "global-ip:" + ip + ":" + pathCategory(path),
            properties.getRateLimitGlobalPerMinute(),
            blockSeconds,
            nowMillis
        ));

        if (ApiRoutePolicy.isChatPath(path)) {
            decisions.add(checkAndConsume(
                "chat-ip:" + ip + ":" + pathCategory(path),
                properties.getRateLimitChatPerMinute(),
                blockSeconds,
                nowMillis
            ));
            if (userId != null) {
                decisions.add(checkAndConsume(
                    "chat-user:" + userId + ":" + pathCategory(path),
                    properties.getRateLimitChatUserPerMinute(),
                    blockSeconds,
                    nowMillis
                ));
            }
        }

        Decision denied = decisions.stream().filter(decision -> !decision.allowed()).findFirst().orElse(null);
        if (denied != null) {
            String requestId = requestId(request);
            response.setHeader("Retry-After", String.valueOf(denied.retryAfterSeconds()));
            response.setHeader("X-RateLimit-Limit", String.valueOf(denied.limit()));
            response.setHeader("X-RateLimit-Remaining", "0");

            logger.warn(
                "{\"event\":\"rate_limit_block\",\"requestId\":\"{}\",\"path\":\"{}\",\"reason\":\"{}\",\"userId\":\"{}\",\"ip\":\"{}\"}",
                requestId,
                path,
                denied.reason(),
                userId == null ? "guest" : userId,
                maskIp(ip)
            );

            SafeErrorResponseWriter.write(
                response,
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too many requests",
                requestId
            );
            return;
        }

        Decision tightest = decisions.stream()
            .min((left, right) -> Integer.compare(left.remaining(), right.remaining()))
            .orElse(null);
        if (tightest != null) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(tightest.limit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, tightest.remaining())));
        }

        filterChain.doFilter(request, response);
    }

    private Decision checkAndConsume(
        String key,
        int limitPerMinute,
        int blockSeconds,
        long nowMillis
    ) {
        int safeLimit = Math.max(1, limitPerMinute);
        BucketState bucket = buckets.computeIfAbsent(key, ignored -> new BucketState());
        synchronized (bucket) {
            if (bucket.blockedUntilMillis > nowMillis) {
                int retryAfterSeconds = (int) Math.ceil((bucket.blockedUntilMillis - nowMillis) / 1000.0);
                return new Decision(false, safeLimit, 0, Math.max(1, retryAfterSeconds), "blocked");
            }

            long minuteWindow = nowMillis / 60_000;
            if (bucket.windowMinute != minuteWindow) {
                bucket.windowMinute = minuteWindow;
                bucket.count = 0;
            }

            bucket.count += 1;
            int remaining = safeLimit - bucket.count;
            if (bucket.count > safeLimit) {
                bucket.blockedUntilMillis = nowMillis + (blockSeconds * 1000L);
                int retryAfterSeconds = Math.max(1, blockSeconds);
                return new Decision(false, safeLimit, 0, retryAfterSeconds, "limit_exceeded");
            }

            return new Decision(true, safeLimit, Math.max(0, remaining), 0, "ok");
        }
    }

    private String normalizeIp(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "unknown";
        }
        return remoteAddr.trim();
    }

    private String normalizeUserId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String pathCategory(String path) {
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        if (path.startsWith("/api/chat")) {
            return "chat";
        }
        if (path.startsWith("/api/conversations")) {
            return "conversations";
        }
        if (path.startsWith("/api/feedback")) {
            return "feedback";
        }
        return "generic";
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

    private record Decision(
        boolean allowed,
        int limit,
        int remaining,
        int retryAfterSeconds,
        String reason
    ) {
    }

    private static final class BucketState {
        long windowMinute = -1L;
        int count = 0;
        long blockedUntilMillis = 0L;
    }
}

package com.fachat.agent.web;

public final class ApiRoutePolicy {

    private ApiRoutePolicy() {
    }

    public static boolean isHealthPath(String path) {
        return "/health".equals(path) || "/actuator/health".equals(path);
    }

    public static boolean isApiPath(String path) {
        return path != null && path.startsWith("/api/");
    }

    public static boolean isChatPath(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/api/chat");
    }

    public static boolean isSensitivePath(String path) {
        if (path == null) {
            return false;
        }

        return path.startsWith("/api/chat")
            || path.startsWith("/api/conversations")
            || path.startsWith("/api/feedback");
    }
}

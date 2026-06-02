package com.fachat.agent.web;

public final class RequestContextKeys {

    public static final String REQUEST_ID = "requestId";
    public static final String REQUEST_START_NANOS = "requestStartNanos";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String INTERNAL_API_HEADER = "X-Internal-Api-Key";

    private RequestContextKeys() {
    }
}

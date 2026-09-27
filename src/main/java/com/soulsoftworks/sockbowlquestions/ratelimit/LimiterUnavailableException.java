package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/** 503 {@code {"error":"limiter_unavailable","policy":...}}: a fail-closed policy with Redis down (D12). */
public class LimiterUnavailableException extends LimitException {

    private final String policy;

    public LimiterUnavailableException(String policy) {
        super("Limiter unavailable for fail-closed policy '" + policy + "'");
        this.policy = policy;
    }

    public String getPolicy() {
        return policy;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.SERVICE_UNAVAILABLE;
    }

    @Override
    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "limiter_unavailable");
        body.put("policy", policy);
        return body;
    }
}

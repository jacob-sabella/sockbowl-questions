package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 429 {@code {"error":"rate_limited","policy":...,"retryAfterSeconds":N,"message":"Too many requests"}}
 * with {@code Retry-After}, {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining: 0}
 * and {@code X-RateLimit-Policy}.
 */
public class RateLimitExceededException extends LimitException {

    private final String policy;
    private final long retryAfterSeconds;
    private final long limit;

    public RateLimitExceededException(String policy, long retryAfterSeconds, long limit) {
        super("Rate limit '" + policy + "' exceeded; retry after " + retryAfterSeconds + "s");
        this.policy = policy;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.limit = limit;
    }

    public static RateLimitExceededException from(String policy, Decision decision) {
        return new RateLimitExceededException(policy, decision.retryAfterSeconds(), decision.limit());
    }

    public String getPolicy() {
        return policy;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public long getLimit() {
        return limit;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }

    @Override
    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "rate_limited");
        body.put("policy", policy);
        body.put("retryAfterSeconds", retryAfterSeconds);
        body.put("message", "Too many requests");
        return body;
    }

    @Override
    public void addHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        if (limit >= 0) {
            headers.set(LimitErrorResponses.X_RATE_LIMIT_LIMIT, Long.toString(limit));
        }
        headers.set(LimitErrorResponses.X_RATE_LIMIT_REMAINING, "0");
        headers.set(LimitErrorResponses.X_RATE_LIMIT_POLICY, policy);
    }
}

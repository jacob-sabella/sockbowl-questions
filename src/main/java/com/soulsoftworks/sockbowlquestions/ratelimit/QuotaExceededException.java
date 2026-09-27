package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 429 {@code {"error":"quota_exceeded","metric":...,"limit":N,"used":N,"resetsAt":"...Z"|null}}.
 * {@code resetsAt} is the next UTC midnight for daily metrics and {@code null}
 * for concurrent/owned ones. A {@code Retry-After} is added only when there is
 * a reset time.
 */
public class QuotaExceededException extends LimitException {

    private final String metric;
    private final long limit;
    private final long used;
    private final Instant resetsAt;
    private final Instant now;

    public QuotaExceededException(String metric, long limit, long used, Instant resetsAt) {
        this(metric, limit, used, resetsAt, null);
    }

    public QuotaExceededException(String metric, long limit, long used, Instant resetsAt, Instant now) {
        super("Quota '" + metric + "' exceeded (" + used + "/" + limit + ")");
        this.metric = metric;
        this.limit = limit;
        this.used = used;
        this.resetsAt = resetsAt;
        this.now = now;
    }

    public String getMetric() {
        return metric;
    }

    public long getLimit() {
        return limit;
    }

    public long getUsed() {
        return used;
    }

    public Instant getResetsAt() {
        return resetsAt;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }

    @Override
    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "quota_exceeded");
        body.put("metric", metric);
        body.put("limit", limit);
        body.put("used", used);
        body.put("resetsAt", resetsAt == null ? null : resetsAt.toString());
        return body;
    }

    @Override
    public void addHeaders(HttpHeaders headers) {
        if (resetsAt != null && now != null) {
            long seconds = Math.max(1, Duration.between(now, resetsAt).toSeconds());
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }
    }
}

package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 403 {@code {"error":"ip_banned","expiresAt":"...Z"}} (D8, AB-02; IP bans always carry a TTL). */
public class IpBannedException extends LimitException {

    private final Instant expiresAt;

    public IpBannedException(Instant expiresAt) {
        super("IP banned");
        this.expiresAt = expiresAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.FORBIDDEN;
    }

    @Override
    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "ip_banned");
        body.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        return body;
    }
}

package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 403 {@code {"error":"banned","reason":...,"expiresAt":"...Z"|null}} (D8, AB-01). */
public class SubjectBannedException extends LimitException {

    private final String reason;
    private final Instant expiresAt;

    public SubjectBannedException(String reason, Instant expiresAt) {
        super("Banned" + (reason == null ? "" : ": " + reason));
        this.reason = reason;
        this.expiresAt = expiresAt;
    }

    public static SubjectBannedException from(SubjectBanChecker.SubjectBan ban) {
        return new SubjectBannedException(ban.reason(), ban.expiresAt());
    }

    public String getReason() {
        return reason;
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
        body.put("error", "banned");
        body.put("reason", reason);
        body.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        return body;
    }
}

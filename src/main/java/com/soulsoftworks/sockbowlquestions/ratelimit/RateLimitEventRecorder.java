package com.soulsoftworks.sockbowlquestions.ratelimit;

import io.lettuce.core.SetArgs;
import io.lettuce.core.XAddArgs;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records limiter rejections to the {@code rl:events} stream (plan m4-limits
 * section 2.1) for the admin usage view. Sampled to at most one event per
 * (policy, subject) per window with {@code SET rl:evsample:... NX EX}, trimmed
 * with {@code XADD MAXLEN ~}. Fire-and-forget: it never blocks the request on
 * Redis and swallows every error.
 */
@Slf4j
public class RateLimitEventRecorder {

    public static final String KIND_RATE = "rate";
    public static final String KIND_QUOTA = "quota";
    public static final String KIND_BAN = "ban";

    private final RateLimitRedis redis;
    private final RateLimitProperties properties;
    private final Clock clock;

    public RateLimitEventRecorder(RateLimitRedis redis, RateLimitProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public void record(String policy, String kind, LimitSubject subject, String path) {
        try {
            String subjectPart = subject.sub() != null
                    ? UsageKeys.userPart(subject.sub())
                    : UsageKeys.ipPart(subject.ip());
            RateLimitProperties.Events events = properties.getEvents();
            Map<String, String> body = new LinkedHashMap<>();
            body.put("ts", Long.toString(clock.millis()));
            body.put("svc", events.getService());
            body.put("policy", policy);
            body.put("kind", kind);
            if (subject.sub() != null) {
                body.put("sub", subject.sub());
            }
            body.put("ip", String.valueOf(subject.ip()));
            body.put("path", path == null ? "" : path);

            var async = redis.async();
            async.set(UsageKeys.eventSample(policy, subjectPart), "1",
                            SetArgs.Builder.nx().ex(Math.max(1, events.getSampleWindow().toSeconds())))
                    .thenCompose(ok -> "OK".equals(ok)
                            ? async.xadd(UsageKeys.events(),
                                    XAddArgs.Builder.maxlen(events.getMaxLen()).approximateTrimming(), body)
                            : java.util.concurrent.CompletableFuture.completedFuture(null))
                    .exceptionally(e -> {
                        log.debug("Could not record rate-limit event: {}", e.toString());
                        return null;
                    });
        } catch (RuntimeException e) {
            log.debug("Could not record rate-limit event: {}", e.toString());
        }
    }
}

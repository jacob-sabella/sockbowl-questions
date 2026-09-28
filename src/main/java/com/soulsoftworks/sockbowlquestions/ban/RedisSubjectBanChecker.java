package com.soulsoftworks.sockbowlquestions.ban;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.SubjectBanChecker;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enforces game-published subject bans in sockbowl-questions (D8, M4-AB-01;
 * plan m4-limits section 2.4). Game's {@code BanRedisMirror} writes
 * {@code ban:{sub}} as JSON {@code {"reason":"...","expiresAt":"<ISO instant>"|null}}
 * with a TTL of {@code expiresAt - now} (no TTL when permanent); this class only
 * reads it.
 *
 * <p>Lookups are cached per subject for {@code sockbowl.bans.cache-ttl} (30s), so
 * a new ban or an unban takes effect here within that window, and the request
 * path costs one Redis {@code GET} per subject per window. Time comes from the
 * injected {@link Clock}, so tests advance past the window without sleeping.
 *
 * <p>Failure handling (D12, A6):
 * <ul>
 *   <li>Redis down with a previously loaded answer for the subject: that answer
 *       keeps being used (a known ban stays enforced until its own
 *       {@code expiresAt}); a WARN is logged at most once a minute.</li>
 *   <li>Redis down and nothing known: fail open (not banned), not cached, so the
 *       next request after Redis recovers is checked again.</li>
 *   <li>A {@code ban:{sub}} key that exists but can't be parsed counts as a
 *       permanent ban: the key's presence is the signal, and a corrupt value must
 *       never let a banned user through.</li>
 * </ul>
 *
 * <p>Registered only with auth on (with auth off there is no subject), which
 * makes {@code LimitsFallbackAutoConfiguration}'s no-op back off.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class RedisSubjectBanChecker implements SubjectBanChecker {

    private static final long WARN_INTERVAL_MS = 60_000;
    /** Stale entries are kept this many TTLs so a Redis outage can reuse them. */
    private static final int STALE_RETENTION_FACTOR = 20;

    private final RateLimitRedis redis;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Cache<String, Loaded> cache;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    /** A Redis answer and when it was read. */
    private record Loaded(Optional<SubjectBan> ban, Instant loadedAt) {
    }

    public RedisSubjectBanChecker(RateLimitRedis redis,
                                  Clock clock,
                                  @Value("${sockbowl.bans.cache-ttl:30s}") Duration cacheTtl,
                                  @Value("${sockbowl.bans.cache-max-size:100000}") long maxSize) {
        this.redis = redis;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(cacheTtl.multipliedBy(STALE_RETENTION_FACTOR).toNanos(), TimeUnit.NANOSECONDS)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
    }

    @Override
    public Optional<SubjectBan> findActiveBan(String sub) {
        if (sub == null || sub.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Loaded cached = cache.getIfPresent(sub);
        if (cached != null && cached.loadedAt().plus(cacheTtl).isAfter(now)) {
            return active(cached.ban(), now);
        }
        try {
            Optional<SubjectBan> ban = read(sub);
            cache.put(sub, new Loaded(ban, now));
            return active(ban, now);
        } catch (RuntimeException e) {
            warn(sub, e);
            // Keep enforcing what we last knew; with nothing known, fail open.
            return cached == null ? Optional.empty() : active(cached.ban(), now);
        }
    }

    /** Drops every cached answer (tests, and a hook for a future invalidation message). */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    private Optional<SubjectBan> read(String sub) {
        String json = redis.sync().get(UsageKeys.ban(sub));
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(parse(sub, json));
    }

    /** Parses game's {@code ban:{sub}} value; anything unreadable is a permanent ban. */
    static SubjectBan parse(String sub, String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw new IllegalStateException("not a JSON object");
            }
            JsonObject object = element.getAsJsonObject();
            String reason = object.has("reason") && !object.get("reason").isJsonNull()
                    ? object.get("reason").getAsString() : null;
            Instant expiresAt = object.has("expiresAt") && !object.get("expiresAt").isJsonNull()
                    ? Instant.parse(object.get("expiresAt").getAsString()) : null;
            return new SubjectBan(reason, expiresAt);
        } catch (RuntimeException e) {
            log.warn("Unreadable ban:{} value; treating it as a permanent ban: {}", sub, e.getMessage());
            return new SubjectBan(null, null);
        }
    }

    private static Optional<SubjectBan> active(Optional<SubjectBan> ban, Instant now) {
        return ban.filter(b -> b.expiresAt() == null || b.expiresAt().isAfter(now));
    }

    private void warn(String sub, RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastWarnMs.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastWarnMs.compareAndSet(last, now)) {
            log.warn("Subject ban lookup failed ({}); using the last known answer or failing open (D12)",
                    e.toString());
        } else {
            log.debug("Subject ban lookup for {} failed: {}", sub, e.toString());
        }
    }
}

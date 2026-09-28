package com.soulsoftworks.sockbowlquestions.ban;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.soulsoftworks.sockbowlquestions.ratelimit.IpBanChecker;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Enforces game-published IP/CIDR bans in sockbowl-questions (D8, M4-AB-02;
 * plan m4-limits section 2.4). Game's {@code IpBanService} mirrors every active
 * ban into the Redis hash {@code ipban:all}: field = ban id, value = JSON
 * {@code {"cidr":"10.1.0.0/16","expiresAtEpochMs":N}}. This class only reads it.
 *
 * <p>The hash is loaded into an in-memory {@link CidrMatcher} and reloaded when
 * the copy is older than {@code sockbowl.ipban.refresh-interval} (15s). The
 * reload happens on the request path, by whichever request first finds the copy
 * stale (the others keep using the current copy meanwhile), so it needs no
 * scheduler and follows the injected {@link Clock}. Expiry is checked on every
 * lookup, so a ban stops matching the moment it expires.
 *
 * <p>Fails open (D12, A6): when Redis can't be read the last loaded set stays
 * in force (still subject to each ban's expiry) and a WARN is logged at most
 * once a minute; before anything was ever loaded nothing matches. A malformed
 * entry is skipped. Registered only with auth on, like game's
 * {@code IpBanService} (only an admin can create IP bans).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class RedisIpBanChecker implements IpBanChecker {

    private static final long WARN_INTERVAL_MS = 60_000;

    private final RateLimitRedis redis;
    private final Clock clock;
    private final Duration refreshInterval;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    private volatile CidrMatcher matcher = CidrMatcher.EMPTY;
    /** When the matcher was last (re)loaded or a load was last attempted; null = never. */
    private volatile Instant lastAttempt;

    public RedisIpBanChecker(RateLimitRedis redis,
                             Clock clock,
                             @Value("${sockbowl.ipban.refresh-interval:15s}") Duration refreshInterval) {
        this.redis = redis;
        this.clock = clock;
        this.refreshInterval = refreshInterval;
    }

    @Override
    public Optional<Instant> findActiveBan(String rawAddress) {
        Instant now = clock.instant();
        Instant attempted = lastAttempt;
        if (attempted == null || !attempted.plus(refreshInterval).isAfter(now)) {
            refreshIfStale(attempted == null);
        }
        return matcher.match(rawAddress, now);
    }

    /**
     * Reloads {@code ipban:all} now.
     *
     * @return true when the mirror was read, false when Redis was unavailable
     */
    public boolean refresh() {
        refreshLock.lock();
        try {
            return load();
        } finally {
            refreshLock.unlock();
        }
    }

    /** Number of bans currently loaded (active or not yet pruned). */
    public int loadedCount() {
        return matcher.size();
    }

    private void refreshIfStale(boolean firstLoad) {
        if (firstLoad) {
            // Nothing loaded yet: every caller waits for the first load, so the
            // first requests after startup are checked against the real set.
            refreshLock.lock();
            try {
                if (lastAttempt == null) {
                    load();
                }
            } finally {
                refreshLock.unlock();
            }
            return;
        }
        if (refreshLock.tryLock()) {
            try {
                Instant attempted = lastAttempt;
                if (attempted == null || !attempted.plus(refreshInterval).isAfter(clock.instant())) {
                    load();
                }
            } finally {
                refreshLock.unlock();
            }
        }
    }

    private boolean load() {
        Instant now = clock.instant();
        try {
            Map<String, String> mirrored = redis.sync().hgetall(UsageKeys.ipBans());
            List<CidrMatcher.Entry> entries = new ArrayList<>();
            for (Map.Entry<String, String> e : mirrored.entrySet()) {
                CidrMatcher.Entry entry = parse(e.getKey(), e.getValue());
                if (entry != null && entry.expiresAt().isAfter(now)) {
                    entries.add(entry);
                }
            }
            matcher = CidrMatcher.of(entries);
            return true;
        } catch (RuntimeException e) {
            warn(e);
            return false;
        } finally {
            lastAttempt = now;
        }
    }

    /** Parses one {@code ipban:all} field value, or returns null (logged) when malformed. */
    static CidrMatcher.Entry parse(String id, String json) {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            return new CidrMatcher.Entry(id,
                    CidrMatcher.Cidr.parse(object.get("cidr").getAsString()),
                    Instant.ofEpochMilli(object.get("expiresAtEpochMs").getAsLong()));
        } catch (RuntimeException e) {
            log.warn("Ignoring a malformed ipban:all entry {}: {}", id, e.getMessage());
            return null;
        }
    }

    private void warn(RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastWarnMs.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastWarnMs.compareAndSet(last, now)) {
            log.warn("IP ban mirror unreadable ({}); keeping the last loaded set (D12)", e.toString());
        } else {
            log.debug("IP ban mirror unreadable: {}", e.toString());
        }
    }
}

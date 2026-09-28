package com.soulsoftworks.sockbowlquestions.ai;

import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.UUID;

/**
 * The per-caller AI concurrency lock {@code ai:inflight:{sub}} (plan m4-limits
 * section 2.1): {@code SET key token NX EX ttl} to take it, and a
 * compare-and-delete to release it, so a caller whose lock already expired can
 * never free somebody else's.
 *
 * <p>Redis errors are <b>not</b> swallowed: the guard treats them as fail-closed
 * (D12).
 */
@Slf4j
public class InflightLock {

    static final String RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final RateLimitRedis redis;

    public InflightLock(RateLimitRedis redis) {
        this.redis = redis;
    }

    /**
     * Tries to take the lock.
     *
     * @return the owner token to pass to {@link #release}, or {@code null} when the lock is held
     * @throws RuntimeException on any Redis error (the caller fails closed)
     */
    public String tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        String reply = redis.sync().set(key, token, SetArgs.Builder.nx().ex(Math.max(1, ttl.toSeconds())));
        return "OK".equals(reply) ? token : null;
    }

    /** Releases the lock if {@code token} still owns it. Never throws; the TTL is the fallback. */
    public void release(String key, String token) {
        if (token == null) {
            return;
        }
        try {
            redis.sync().eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{key}, token);
        } catch (RuntimeException e) {
            log.warn("Could not release AI concurrency lock {} (it expires on its own): {}", key, e.toString());
        }
    }
}

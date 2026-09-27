package com.soulsoftworks.sockbowlquestions.ratelimit;

import java.util.concurrent.TimeUnit;

/**
 * The outcome of one {@link RateLimitService#tryConsume} call.
 *
 * @param allowed         the request may proceed
 * @param remaining       tokens left after this call ({@code -1} when unknown)
 * @param retryAfterNanos how long until enough tokens are back (0 when allowed)
 * @param limit           the effective (tier-scaled) capacity ({@code -1} when unknown)
 * @param failedOpen      Redis was unavailable and a fail-open policy let the call through
 * @param limiterUnavailable Redis was unavailable and a fail-closed policy refused the call
 */
public record Decision(boolean allowed, long remaining, long retryAfterNanos, long limit,
                       boolean failedOpen, boolean limiterUnavailable) {

    /** Allowed without consulting a bucket (limiter disabled or unknown policy). */
    public static Decision unlimited() {
        return new Decision(true, -1, 0, -1, false, false);
    }

    public static Decision allowed(long remaining, long limit) {
        return new Decision(true, remaining, 0, limit, false, false);
    }

    public static Decision rejected(long retryAfterNanos, long limit) {
        return new Decision(false, 0, Math.max(0, retryAfterNanos), limit, false, false);
    }

    public static Decision failedOpen(long limit) {
        return new Decision(true, -1, 0, limit, true, false);
    }

    /** Redis was down and the policy is fail-closed (D12): answer 503. */
    public static Decision unavailable() {
        return new Decision(false, -1, 0, -1, false, true);
    }

    /** Seconds to put in {@code Retry-After}: rounded up, at least 1 when rejected. */
    public long retryAfterSeconds() {
        if (allowed) {
            return 0;
        }
        long seconds = TimeUnit.NANOSECONDS.toSeconds(retryAfterNanos);
        if (TimeUnit.SECONDS.toNanos(seconds) < retryAfterNanos) {
            seconds++;
        }
        return Math.max(1, seconds);
    }
}

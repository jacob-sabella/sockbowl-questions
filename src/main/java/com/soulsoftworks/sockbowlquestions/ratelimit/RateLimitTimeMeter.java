package com.soulsoftworks.sockbowlquestions.ratelimit;

import io.github.bucket4j.TimeMeter;

import java.time.Clock;
import java.time.Instant;

/**
 * bucket4j {@link TimeMeter} backed by the injected {@link Clock}, so a test
 * that swaps in a mutable clock can refill buckets deterministically instead of
 * sleeping. Used both as the Redis proxy manager's client clock and as the
 * precision of the local STOMP buckets.
 */
public class RateLimitTimeMeter implements TimeMeter {

    private final Clock clock;

    public RateLimitTimeMeter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long currentTimeNanos() {
        Instant now = clock.instant();
        return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
    }

    @Override
    public boolean isWallClockBased() {
        return true;
    }
}

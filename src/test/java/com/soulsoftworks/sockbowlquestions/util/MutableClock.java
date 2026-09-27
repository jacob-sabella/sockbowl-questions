package com.soulsoftworks.sockbowlquestions.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A settable {@link Clock} for limiter, quota and TTL tests (plan m4-limits
 * section 2.2): declare it as the {@code Clock} bean (it replaces
 * {@code LimitsClockConfig}'s system clock) or pass it to the services directly,
 * then {@link #advance} instead of sleeping, so recovery tests are deterministic.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    /** Starts at the current wall-clock time (bucket TTLs in Redis are real time). */
    public static MutableClock startingNow() {
        return new MutableClock(Instant.now());
    }

    public void advance(Duration duration) {
        now.updateAndGet(i -> i.plus(duration));
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}

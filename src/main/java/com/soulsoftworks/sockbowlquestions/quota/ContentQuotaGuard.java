package com.soulsoftworks.sockbowlquestions.quota;

import com.soulsoftworks.sockbowlquestions.ai.InflightLock;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitEventRecorder;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Content quotas (D10, M4-UQ-01; plan m4-limits section 2.3) for the operations
 * that create an <b>owned</b> packet:
 *
 * <ul>
 *   <li>{@code packets-owned}: the number of packets whose {@code ownerId} is the
 *       caller, counted in Neo4j, checked by {@link #checkPacketsOwned} before
 *       {@code createPacket}, an owned {@code import-random} and
 *       {@code POST /api/packets/generate}. It recovers when a packet is deleted.
 *       Nothing is charged, so there is nothing to refund.</li>
 *   <li>{@code imports}: a daily counter (UTC day) charged by
 *       {@link #chargeImport} for an owned {@code import-random}, and refunded when
 *       the import then fails.</li>
 * </ul>
 *
 * <p>The limit is the {@code quota:override:{sub}} value when set, else the tier
 * default ({@code sockbowl.quota.tiers.*}); ADMIN and SERVICE are unlimited. A
 * game-only EPHEMERAL import (D15: guests and players) has no owner, so neither
 * quota applies to it; it is bounded by the {@code import}/{@code import-ip}
 * rate policies instead.
 *
 * <p>Skipped entirely with {@code sockbowl.auth.enabled=false} (every caller is
 * an anonymous guest there, whose D10 quota is 0) and with
 * {@code sockbowl.quota.enabled=false}. Redis failures fail open (D12): the
 * override lookup falls back to the tier default and the daily counter is
 * skipped.
 *
 * <p>{@link #checkPacketsOwned} alone is a plain read-then-check: two
 * concurrent callers can both read the count below the limit and both then
 * create, overshooting it (Q-V1-01). {@link #withPacketsOwnedSlot} closes that
 * race for every entry point that actually creates an owned packet
 * ({@code createPacket}, an owned {@code import-random},
 * {@code POST /api/packets/generate}/{@code generateAndAddTossup}'s packet
 * creation, and, since INT1, M3's {@code importPacket(dryRun: false)} and
 * {@code clonePacket}): it serializes one owner's slot check-and-create behind
 * a short per-owner Redis lock, so the count it checks can never go stale
 * before the create it guards commits.
 */
@Slf4j
@Component
public class ContentQuotaGuard {

    /** {@code SET NX} lock guarding one owner's packets-owned slot. */
    private static final String LOCK_PREFIX = "quota:lock:packets-owned:";
    private static final Duration DEFAULT_LOCK_TTL = Duration.ofMillis(10_000);
    private static final Duration DEFAULT_SPIN_INTERVAL = Duration.ofMillis(25);
    private static final long WARN_INTERVAL_MS = 60_000;

    private final QuotaService quotaService;
    private final LimitSubjectResolver subjectResolver;
    private final PacketRepository packetRepository;
    private final RateLimitEventRecorder eventRecorder;
    // Same SET-NX-then-compare-and-delete primitive as the AI concurrency lock
    // (ai:inflight:{sub}); only the fail-open-vs-fail-closed handling differs.
    private final InflightLock lock;
    private final boolean authEnabled;
    private final Duration lockTtl;
    private final Duration spinTimeout;
    private final Duration spinInterval;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public ContentQuotaGuard(QuotaService quotaService,
                             LimitSubjectResolver subjectResolver,
                             PacketRepository packetRepository,
                             RateLimitEventRecorder eventRecorder,
                             RateLimitRedis redis,
                             QuotaProperties quotaProperties,
                             @Value("${sockbowl.auth.enabled:false}") boolean authEnabled) {
        this(quotaService, subjectResolver, packetRepository, eventRecorder, redis, authEnabled,
                DEFAULT_LOCK_TTL, quotaProperties.getPacketsOwnedLockSpinTimeout(), DEFAULT_SPIN_INTERVAL);
    }

    /** As above, with the lock's timing overridden (tests only: a fast spin timeout/interval). */
    ContentQuotaGuard(QuotaService quotaService,
                      LimitSubjectResolver subjectResolver,
                      PacketRepository packetRepository,
                      RateLimitEventRecorder eventRecorder,
                      RateLimitRedis redis,
                      boolean authEnabled,
                      Duration lockTtl,
                      Duration spinTimeout,
                      Duration spinInterval) {
        this.quotaService = quotaService;
        this.subjectResolver = subjectResolver;
        this.packetRepository = packetRepository;
        this.eventRecorder = eventRecorder;
        this.lock = new InflightLock(redis);
        this.authEnabled = authEnabled;
        this.lockTtl = lockTtl;
        this.spinTimeout = spinTimeout;
        this.spinInterval = spinInterval;
    }

    /**
     * Runs {@code create} exactly once, having first rejected it under
     * {@link #checkPacketsOwned} the same way that method would on its own,
     * but with the count-check-create sequence serialized per owner (Q-V1-01)
     * so 24 concurrent callers at a limit of 2 can never create more than 2.
     *
     * <p>Acquires {@code quota:lock:packets-owned:{sub}} ({@code SET NX}, a
     * 10s TTL, via the same {@link InflightLock} primitive as the AI
     * concurrency lock), spin-waiting up to {@code sockbowl.quota.packets-owned-lock-spin-timeout}
     * (FIX3-Q item 2; default ~1.5s, lowered from an earlier 5s that pinned a
     * servlet thread that long under contention) for a contended lock before
     * giving up with a 429 {@code rate_limited} (a short
     * {@code retryAfterSeconds}, not a quota rejection: the caller should
     * simply retry). The lock is released by a compare-and-delete keyed on a
     * random token, so this caller can never release a lock it does not still
     * hold. A caller with no owner ({@code authEnabled=false}, no quotas, or
     * {@code ownerId == null}) skips the lock entirely, same as
     * {@link #checkPacketsOwned}.
     *
     * <p>When Redis itself is unreachable (D12), the lock attempt fails open
     * immediately (no spin-wait) with a rate-limited WARN, and {@code create}
     * runs under the same unlocked, best-effort check {@link #checkPacketsOwned}
     * already made before this method existed.
     */
    public <T> T withPacketsOwnedSlot(String ownerId, Supplier<T> create) {
        LimitSubject subject = ownerSubject(ownerId);
        if (subject == null) {
            return create.get();
        }
        String token = tryAcquireLock(subject.sub());
        if (token == null) {
            checkPacketsOwned(ownerId);
            return create.get();
        }
        try {
            checkPacketsOwned(ownerId);
            return create.get();
        } finally {
            releaseLock(subject.sub(), token);
        }
    }

    /**
     * @return a token identifying this holder's acquisition, or {@code null}
     *         when Redis is unreachable (fail open, D12; a WARN is logged)
     * @throws RateLimitExceededException 429 {@code rate_limited} when the lock
     *         is still held by someone else after the spin-wait timeout
     */
    private String tryAcquireLock(String sub) {
        String key = LOCK_PREFIX + sub;
        long deadlineNanos = System.nanoTime() + spinTimeout.toNanos();
        while (true) {
            String token;
            try {
                token = lock.tryAcquire(key, lockTtl);
            } catch (RuntimeException e) {
                warnFailOpen("packets-owned lock Redis unavailable", e);
                return null;
            }
            if (token != null) {
                return token;
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw new RateLimitExceededException("packets-owned-lock", 1, -1);
            }
            try {
                Thread.sleep(spinInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RateLimitExceededException("packets-owned-lock", 1, -1);
            }
        }
    }

    private void releaseLock(String sub, String token) {
        // InflightLock.release() never throws; a failed release just leaves
        // the lock to expire on its own via its TTL.
        lock.release(LOCK_PREFIX + sub, token);
    }

    private void warnFailOpen(String what, RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastWarnMs.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastWarnMs.compareAndSet(last, now)) {
            log.warn("{}; failing open (D12): {}", what, e.toString());
        } else {
            log.debug("{}: {}", what, e.toString());
        }
    }

    /**
     * Rejects creating one more packet owned by {@code ownerId} when that would
     * exceed its {@code packets-owned} quota.
     *
     * @param ownerId the owner the new packet will get; {@code null} (no owner) is never limited
     * @throws QuotaExceededException 429 {@code quota_exceeded}, {@code resetsAt: null}
     */
    public void checkPacketsOwned(String ownerId) {
        LimitSubject subject = ownerSubject(ownerId);
        if (subject == null) {
            return;
        }
        // Q-V1-04: an unreachable override lookup is "unknown", not "use the tier
        // default" — the count already on record may exceed that default (e.g. an
        // admin override raised the owner's real limit), and rejecting on the
        // fallback in that case would reject a create D12 says must be allowed.
        OptionalLong effective = quotaService.effectiveLimitOrUnknown(subject, UsageKeys.PACKETS_OWNED);
        if (effective.isEmpty()) {
            warnFailOpen("packets-owned limit unknown (override lookup unavailable)",
                    new IllegalStateException("effectiveLimitOrUnknown was empty for " + subject.sub()));
            return;
        }
        long limit = effective.getAsLong();
        if (limit < 0) {
            return;
        }
        long owned = packetRepository.countByOwnerId(ownerId);
        if (owned >= limit) {
            eventRecorder.record(UsageKeys.PACKETS_OWNED, RateLimitEventRecorder.KIND_QUOTA, subject, currentPath());
            throw new QuotaExceededException(UsageKeys.PACKETS_OWNED, limit, owned, null);
        }
    }

    /**
     * Charges one owned import to today's {@code imports} counter.
     *
     * @return a charge to {@link ImportCharge#refund() refund} if the import then fails
     * @throws QuotaExceededException 429 {@code quota_exceeded} (nothing is charged)
     */
    public ImportCharge chargeImport(String ownerId) {
        LimitSubject subject = ownerSubject(ownerId);
        if (subject == null) {
            return ImportCharge.NONE;
        }
        try {
            QuotaStatus status = quotaService.consumeDaily(subject, UsageKeys.IMPORTS, 1, false);
            return status.used() >= 0 ? new ImportCharge(this, subject) : ImportCharge.NONE;
        } catch (QuotaExceededException e) {
            eventRecorder.record(UsageKeys.IMPORTS, RateLimitEventRecorder.KIND_QUOTA, subject, currentPath());
            throw e;
        }
    }

    /**
     * The subject quotas are charged to: the caller's tier with the owner's
     * {@code sub}, or {@code null} when quotas don't apply.
     */
    private LimitSubject ownerSubject(String ownerId) {
        if (!authEnabled || !quotaService.isEnabled() || ownerId == null || ownerId.isBlank()) {
            return null;
        }
        LimitSubject caller = subjectResolver.current();
        return new LimitSubject(ownerId, caller.ip(), caller.tier());
    }

    private static String currentPath() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest().getRequestURI();
        }
        return "";
    }

    /** One charged {@code imports} unit; {@link #refund()} gives it back (idempotent). */
    public static final class ImportCharge {

        /** Nothing charged; {@link #refund()} is a no-op. */
        public static final ImportCharge NONE = new ImportCharge(null, null);

        private final ContentQuotaGuard guard;
        private final LimitSubject subject;
        private boolean refunded;

        private ImportCharge(ContentQuotaGuard guard, LimitSubject subject) {
            this.guard = guard;
            this.subject = subject;
        }

        public synchronized void refund() {
            if (guard == null || refunded) {
                return;
            }
            refunded = true;
            guard.quotaService.refundDaily(subject, UsageKeys.IMPORTS, 1);
        }
    }
}

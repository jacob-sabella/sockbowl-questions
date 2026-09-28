package com.soulsoftworks.sockbowlquestions.quota;

import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitEventRecorder;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
 * skipped. The owned count is a read-then-create, so two concurrent creates at
 * the limit can overshoot it by one; that is accepted for a soft content cap.
 */
@Component
public class ContentQuotaGuard {

    private final QuotaService quotaService;
    private final LimitSubjectResolver subjectResolver;
    private final PacketRepository packetRepository;
    private final RateLimitEventRecorder eventRecorder;
    private final boolean authEnabled;

    public ContentQuotaGuard(QuotaService quotaService,
                             LimitSubjectResolver subjectResolver,
                             PacketRepository packetRepository,
                             RateLimitEventRecorder eventRecorder,
                             @Value("${sockbowl.auth.enabled:false}") boolean authEnabled) {
        this.quotaService = quotaService;
        this.subjectResolver = subjectResolver;
        this.packetRepository = packetRepository;
        this.eventRecorder = eventRecorder;
        this.authEnabled = authEnabled;
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
        long limit = quotaService.effectiveLimit(subject, UsageKeys.PACKETS_OWNED);
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

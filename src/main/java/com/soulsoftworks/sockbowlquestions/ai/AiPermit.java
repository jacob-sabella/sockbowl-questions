package com.soulsoftworks.sockbowlquestions.ai;

import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;

/**
 * Permission for one AI generation, from {@link AiGenerationGuard#acquire}.
 * Use it in try-with-resources and call {@link #success(long)} once the
 * generation worked:
 *
 * <pre>{@code
 * try (AiPermit permit = guard.acquire(context)) {
 *     Packet packet = strategy.generatePacket(...);
 *     permit.success(questions);
 *     return packet;
 * }
 * }</pre>
 *
 * <p>{@link #close()} releases the concurrency lock. When the permit was never
 * marked successful (the generation threw), it also refunds the per-user
 * {@code ai.generations} quota and the global {@code ai.serverkey} budget, so a
 * failed call costs the caller nothing but its rate-limit token.
 */
public final class AiPermit implements AutoCloseable {

    private final AiGenerationGuard guard;
    private final LimitSubject subject;
    private final String lockKey;
    private final String lockToken;
    private final boolean userQuotaCharged;
    private final boolean globalBudgetCharged;
    private boolean succeeded;
    private boolean closed;

    AiPermit(AiGenerationGuard guard, LimitSubject subject, String lockKey, String lockToken,
             boolean userQuotaCharged, boolean globalBudgetCharged) {
        this.guard = guard;
        this.subject = subject;
        this.lockKey = lockKey;
        this.lockToken = lockToken;
        this.userQuotaCharged = userQuotaCharged;
        this.globalBudgetCharged = globalBudgetCharged;
    }

    public LimitSubject subject() {
        return subject;
    }

    /** True when this call runs on the server's key and was charged to the quota or budget. */
    public boolean charged() {
        return userQuotaCharged || globalBudgetCharged;
    }

    /**
     * Marks the generation successful (no refund on close) and records how many
     * questions it produced ({@code ai.questions}, for visibility only; A1).
     */
    public void success(long questions) {
        if (succeeded || closed) {
            return;
        }
        succeeded = true;
        guard.recordQuestions(subject, questions);
    }

    public boolean succeeded() {
        return succeeded;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!succeeded) {
                guard.refund(subject, userQuotaCharged, globalBudgetCharged);
            }
        } finally {
            guard.releaseLock(lockKey, lockToken);
        }
    }
}

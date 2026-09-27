package com.soulsoftworks.sockbowlquestions.quota;

import java.time.Instant;

/**
 * A quota reading.
 *
 * @param metric   the metric name ({@code UsageKeys} constants)
 * @param used     the count after this call ({@code -1} when unknown, e.g. Redis down)
 * @param limit    the effective limit ({@code -1} = unlimited)
 * @param resetsAt the next UTC midnight for daily metrics, {@code null} otherwise
 */
public record QuotaStatus(String metric, long used, long limit, Instant resetsAt) {

    public boolean unlimited() {
        return limit < 0;
    }

    /** Remaining uses, or {@code Long.MAX_VALUE} when unlimited/unknown. */
    public long remaining() {
        if (limit < 0 || used < 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, limit - used);
    }
}

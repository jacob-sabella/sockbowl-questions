package com.soulsoftworks.sockbowlquestions.quota;

import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimiterUnavailableException;
import com.soulsoftworks.sockbowlquestions.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.Tier;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import io.lettuce.core.ScriptOutputType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-user quotas (D10, D11; plan m4-limits section 2.3).
 *
 * <p>The effective limit is {@code quota:override:{sub}} when set, else the
 * tier default from {@link QuotaProperties}. Daily counters live under
 * {@link UsageKeys#daily} (UTC days) and are checked and incremented
 * atomically by {@code quota/quota-check.lua}; a failed guarded operation calls
 * {@link #refundDaily} to give the unit back. SERVICE callers are never
 * counted; ADMIN callers are counted (for visibility) but never limited.
 *
 * <p>Redis failures fail open (D12) unless the caller asks for fail-closed, in
 * which case {@link LimiterUnavailableException} is thrown.
 */
@Slf4j
public class QuotaService {

    static final String CHECK_SCRIPT = loadScript("quota/quota-check.lua");
    // A missing counter (expired, or reset by an admin) is left missing: DECRBY
    // would otherwise create it without a TTL and it would never expire.
    static final String REFUND_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            local v = redis.call('DECRBY', KEYS[1], tonumber(ARGV[1]))
            if v < 0 then
              redis.call('SET', KEYS[1], 0, 'KEEPTTL')
              v = 0
            end
            return v
            """;

    private static final long WARN_INTERVAL_MS = 60_000;

    private final QuotaProperties properties;
    private final RateLimitRedis redis;
    private final Clock clock;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    public QuotaService(QuotaProperties properties, RateLimitRedis redis, Clock clock) {
        this.properties = properties;
        this.redis = redis;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** The next UTC midnight: when every daily counter resets. */
    public Instant nextUtcMidnight() {
        return today().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * The limit that applies to a subject: {@code -1} when quotas are off or the
     * tier skips them, otherwise the override (if any) or the tier default. Used
     * directly by the concurrent/owned metrics (hosted sessions, packets owned)
     * and by {@link #dailyStatus} for display. A Redis failure falls back to the
     * tier default — fine for a status read, but see {@link #effectiveLimitOrUnknown}
     * for a caller (like {@code checkPacketsOwned}, Q-V1-04) that actually gates a
     * create on the result and must not silently reject on a wrong fallback.
     */
    public long effectiveLimit(LimitSubject subject, String metric) {
        return effectiveLimitOrUnknown(subject, metric)
                .orElseGet(() -> properties.defaultLimit(subject.tier(), metric));
    }

    /**
     * Like {@link #effectiveLimit}, but tells the two D12 fail-open cases apart:
     * {@link OptionalLong#empty()} means the override lookup itself failed
     * (Redis unavailable) — the limit is genuinely <b>unknown</b>, not "no
     * override, use the tier default". A caller that enforces a hard cap (an
     * owned-resource check, not a daily counter) must treat "unknown" as
     * "allow" (D12), since comparing a live count against a possibly-wrong
     * fallback can reject a create that a reachable override would have
     * allowed (Q-V1-04) — the opposite of failing open.
     */
    public OptionalLong effectiveLimitOrUnknown(LimitSubject subject, String metric) {
        if (!properties.isEnabled() || subject.tier().skipsQuotas()) {
            return OptionalLong.of(QuotaProperties.UNLIMITED);
        }
        long fallback = properties.defaultLimit(subject.tier(), metric);
        if (subject.sub() == null) {
            return OptionalLong.of(fallback);
        }
        try {
            String override = redis.sync().hget(UsageKeys.quotaOverride(subject.sub()), metric);
            return OptionalLong.of(override == null ? fallback : Long.parseLong(override.trim()));
        } catch (RuntimeException e) {
            warn("override lookup", e);
            return OptionalLong.empty();
        }
    }

    /** Charges one unit of a daily metric; see {@link #consumeDaily(LimitSubject, String, long, boolean)}. */
    public QuotaStatus consumeDaily(LimitSubject subject, String metric) {
        return consumeDaily(subject, metric, 1, false);
    }

    /**
     * Atomically checks and charges {@code amount} units of a daily metric.
     *
     * @throws QuotaExceededException      when the charge would exceed the limit (nothing is charged)
     * @throws LimiterUnavailableException when Redis is down and {@code failClosed}
     */
    public QuotaStatus consumeDaily(LimitSubject subject, String metric, long amount, boolean failClosed) {
        Instant resetsAt = nextUtcMidnight();
        if (!properties.isEnabled() || subject.tier() == Tier.SERVICE) {
            return new QuotaStatus(metric, -1, QuotaProperties.UNLIMITED, resetsAt);
        }
        boolean skip = subject.tier().skipsQuotas();
        long defaultLimit = skip ? QuotaProperties.UNLIMITED : properties.defaultLimit(subject.tier(), metric);
        if (subject.sub() == null && defaultLimit == 0) {
            // Anonymous callers have no override; reject without a round trip.
            throw exceeded(metric, 0, 0, resetsAt);
        }
        String counter = UsageKeys.daily(idOf(subject), metric, today());
        String overrideKey = skip || subject.sub() == null ? "" : UsageKeys.quotaOverride(subject.sub());
        return runCheck(metric, counter, overrideKey, defaultLimit, amount, failClosed, resetsAt);
    }

    /** Gives back units charged by {@link #consumeDaily} when the guarded operation failed. */
    public void refundDaily(LimitSubject subject, String metric, long amount) {
        if (!properties.isEnabled() || subject.tier() == Tier.SERVICE) {
            return;
        }
        refund(UsageKeys.daily(idOf(subject), metric, today()), amount);
    }

    /**
     * Atomically checks and charges a global daily budget
     * ({@code usage:global:{metric}:d:{yyyyMMdd}}), e.g. D11's server-key budget.
     */
    public QuotaStatus consumeGlobalDaily(String metric, long limit, long amount, boolean failClosed) {
        Instant resetsAt = nextUtcMidnight();
        if (!properties.isEnabled()) {
            return new QuotaStatus(metric, -1, QuotaProperties.UNLIMITED, resetsAt);
        }
        return runCheck(metric, UsageKeys.globalDaily(metric, today()), "", limit, amount, failClosed, resetsAt);
    }

    public void refundGlobalDaily(String metric, long amount) {
        if (!properties.isEnabled()) {
            return;
        }
        refund(UsageKeys.globalDaily(metric, today()), amount);
    }

    /** Today's count of a daily metric without charging it ({@code -1} when Redis is down). */
    public QuotaStatus dailyStatus(LimitSubject subject, String metric) {
        Instant resetsAt = nextUtcMidnight();
        long limit = effectiveLimit(subject, metric);
        try {
            String value = redis.sync().get(UsageKeys.daily(idOf(subject), metric, today()));
            return new QuotaStatus(metric, value == null ? 0 : Long.parseLong(value), limit, resetsAt);
        } catch (RuntimeException e) {
            warn("status read", e);
            return new QuotaStatus(metric, -1, limit, resetsAt);
        }
    }

    private QuotaStatus runCheck(String metric, String counter, String overrideKey, long defaultLimit,
                                 long amount, boolean failClosed, Instant resetsAt) {
        List<Object> result;
        try {
            result = redis.sync().eval(CHECK_SCRIPT, ScriptOutputType.MULTI,
                    new String[]{counter, overrideKey},
                    Long.toString(defaultLimit),
                    Long.toString(Math.max(1, properties.getDailyTtl().toSeconds())),
                    Long.toString(amount),
                    metric);
        } catch (RuntimeException e) {
            warn("check of '" + metric + "'", e);
            if (failClosed) {
                throw new LimiterUnavailableException(metric);
            }
            return new QuotaStatus(metric, -1, defaultLimit, resetsAt);
        }
        boolean allowed = ((Number) result.get(0)).longValue() == 1;
        long used = ((Number) result.get(1)).longValue();
        long limit = ((Number) result.get(2)).longValue();
        if (!allowed) {
            throw exceeded(metric, limit, used, resetsAt);
        }
        return new QuotaStatus(metric, used, limit, resetsAt);
    }

    private void refund(String counter, long amount) {
        try {
            redis.sync().eval(REFUND_SCRIPT, ScriptOutputType.INTEGER, new String[]{counter}, Long.toString(amount));
        } catch (RuntimeException e) {
            warn("refund", e);
        }
    }

    private QuotaExceededException exceeded(String metric, long limit, long used, Instant resetsAt) {
        return new QuotaExceededException(metric, limit, used, resetsAt, clock.instant());
    }

    /** The id a subject's counters are keyed by: its {@code sub}, or {@code ip:{addr}} for a guest. */
    static String idOf(LimitSubject subject) {
        return subject.sub() != null ? subject.sub() : UsageKeys.ipPart(subject.ip());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private void warn(String what, RuntimeException e) {
        long now = clock.millis();
        long last = lastWarnMs.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastWarnMs.compareAndSet(last, now)) {
            log.warn("Quota Redis unavailable ({}); failing open: {}", what, e.toString());
        } else {
            log.debug("Quota Redis unavailable ({}): {}", what, e.toString());
        }
    }

    private static String loadScript(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + path, e);
        }
    }
}

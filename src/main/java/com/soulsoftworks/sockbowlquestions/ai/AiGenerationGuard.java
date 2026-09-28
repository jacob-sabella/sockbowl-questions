package com.soulsoftworks.sockbowlquestions.ai;

import com.soulsoftworks.sockbowlquestions.config.AiSecurityProperties;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.quota.QuotaProperties;
import com.soulsoftworks.sockbowlquestions.quota.QuotaService;
import com.soulsoftworks.sockbowlquestions.quota.QuotaStatus;
import com.soulsoftworks.sockbowlquestions.ratelimit.Decision;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimiterUnavailableException;
import com.soulsoftworks.sockbowlquestions.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitEventRecorder;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitService;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import io.lettuce.core.ScriptOutputType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * AI cost controls (D11, D12; plan m4-limits section 2.6). Every AI generation,
 * REST {@code POST /api/packets/generate} and GraphQL {@code generateAndAddTossup}
 * alike, runs inside a permit from {@link #acquire}, which in order:
 *
 * <ol>
 *   <li>rejects a server-key call that names a model outside
 *       {@code sockbowl.ai.server-key.allowed-models} (400, costs nothing);</li>
 *   <li>charges the {@value #POLICY_AI_GENERATE} rate policy, BYO-key calls
 *       included (fails <b>closed</b>: 503 {@code limiter_unavailable});</li>
 *   <li>takes the {@code ai:inflight:{sub}} concurrency lock; a second concurrent
 *       call gets 429 {@value #POLICY_AI_CONCURRENCY} (fails closed);</li>
 *   <li>only for server-key calls (no {@code X-API-Key}): charges the per-user
 *       {@code ai.generations} daily quota (fails <b>open</b>) and the global
 *       {@code ai.serverkey} daily budget (fails closed).</li>
 * </ol>
 *
 * <p>{@link AiPermit#close()} releases the lock and, unless the generation
 * succeeded, refunds the quota and the budget.
 *
 * <p>D12 escape hatch: with {@code sockbowl.ratelimit.enabled=false} the rate
 * charge and the lock are skipped and the global budget fails open, so local dev
 * without Redis still works. With {@code sockbowl.auth.enabled=false} every caller
 * is an anonymous guest (whose D10 AI quota is 0), so the per-user quota is not
 * applied; the global budget still caps server-key spend.
 */
@Slf4j
@Component
public class AiGenerationGuard {

    public static final String POLICY_AI_GENERATE = "ai-generate";
    public static final String POLICY_AI_CONCURRENCY = "ai-concurrency";

    /** INCRBY with a TTL set on first creation, for the visibility-only counters. */
    static final String COUNT_SCRIPT = """
            local v = redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
            if v == tonumber(ARGV[1]) then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            end
            return v
            """;

    private final RateLimitService rateLimitService;
    private final QuotaService quotaService;
    private final QuotaProperties quotaProperties;
    private final LimitSubjectResolver subjectResolver;
    private final RateLimitEventRecorder eventRecorder;
    private final RateLimitRedis redis;
    private final AiSecurityProperties properties;
    private final InflightLock inflightLock;
    private final Clock clock;
    private final boolean authEnabled;

    public AiGenerationGuard(RateLimitService rateLimitService,
                             QuotaService quotaService,
                             QuotaProperties quotaProperties,
                             LimitSubjectResolver subjectResolver,
                             RateLimitEventRecorder eventRecorder,
                             RateLimitRedis redis,
                             AiSecurityProperties properties,
                             Clock clock,
                             @Value("${sockbowl.auth.enabled:false}") boolean authEnabled) {
        this.rateLimitService = rateLimitService;
        this.quotaService = quotaService;
        this.quotaProperties = quotaProperties;
        this.subjectResolver = subjectResolver;
        this.eventRecorder = eventRecorder;
        this.redis = redis;
        this.properties = properties;
        this.inflightLock = new InflightLock(redis);
        this.clock = clock;
        this.authEnabled = authEnabled;
    }

    /** {@link #acquire(LimitSubject, AiRequestContext)} for the caller bound to this thread. */
    public AiPermit acquire(AiRequestContext context) {
        return acquire(subjectResolver.current(), context);
    }

    /**
     * Admits one generation for {@code subject}, or throws.
     *
     * @throws InvalidApiRequestException   a server-key call named a model that is not allowed (400)
     * @throws RateLimitExceededException   {@value #POLICY_AI_GENERATE} or {@value #POLICY_AI_CONCURRENCY} (429)
     * @throws QuotaExceededException       {@code ai.generations} or {@code ai.serverkey} (429)
     * @throws LimiterUnavailableException  Redis is down for a fail-closed check (503)
     */
    public AiPermit acquire(LimitSubject subject, AiRequestContext context) {
        boolean serverKey = context == null || !context.hasCustomConfig();
        if (serverKey) {
            checkServerKeyModel(context);
        }
        boolean limiterEnabled = rateLimitService.isEnabled();

        if (limiterEnabled) {
            Decision decision = rateLimitService.tryConsume(POLICY_AI_GENERATE, subject);
            if (decision.limiterUnavailable()) {
                throw new LimiterUnavailableException(POLICY_AI_GENERATE);
            }
            if (!decision.allowed()) {
                eventRecorder.record(POLICY_AI_GENERATE, RateLimitEventRecorder.KIND_RATE, subject, currentPath());
                throw RateLimitExceededException.from(POLICY_AI_GENERATE, decision);
            }
        }

        String lockKey = UsageKeys.aiInflight(idOf(subject));
        String lockToken = null;
        if (limiterEnabled) {
            try {
                lockToken = inflightLock.tryAcquire(lockKey, properties.getInflightTtl());
            } catch (RuntimeException e) {
                log.warn("AI concurrency lock unavailable; refusing the generation (fail closed): {}", e.toString());
                throw new LimiterUnavailableException(POLICY_AI_CONCURRENCY);
            }
            if (lockToken == null) {
                eventRecorder.record(POLICY_AI_CONCURRENCY, RateLimitEventRecorder.KIND_RATE, subject, currentPath());
                throw new RateLimitExceededException(POLICY_AI_CONCURRENCY,
                        properties.getConcurrencyRetryAfter().toSeconds(), 1);
            }
        }

        boolean userCharged = false;
        boolean globalCharged = false;
        try {
            if (serverKey) {
                if (authEnabled) {
                    QuotaStatus user = quotaService.consumeDaily(subject, UsageKeys.AI_GENERATIONS, 1, false);
                    userCharged = user.used() >= 0;
                }
                QuotaStatus global = quotaService.consumeGlobalDaily(UsageKeys.AI_SERVERKEY,
                        properties.getServerKey().getDailyBudget(), 1, limiterEnabled);
                globalCharged = global.used() >= 0;
            }
        } catch (RuntimeException e) {
            if (e instanceof QuotaExceededException q) {
                eventRecorder.record(q.getMetric(), RateLimitEventRecorder.KIND_QUOTA, subject, currentPath());
            }
            refund(subject, userCharged, globalCharged);
            releaseLock(lockKey, lockToken);
            throw e;
        }
        return new AiPermit(this, subject, lockKey, lockToken, userCharged, globalCharged);
    }

    private void checkServerKeyModel(AiRequestContext context) {
        String model = context == null ? null : context.getModel();
        if (model == null || model.isBlank()) {
            return;
        }
        List<String> allowed = properties.getServerKey().getAllowedModels();
        boolean ok = allowed != null && allowed.stream()
                .anyMatch(m -> m != null && m.trim().equals(model.trim()));
        if (!ok) {
            throw new InvalidApiRequestException("Model '" + model
                    + "' is not available with the server's API key. Allowed: "
                    + (allowed == null || allowed.isEmpty() ? "none" : String.join(", ", allowed))
                    + ". Provide your own X-API-Key to use another model.");
        }
    }

    void refund(LimitSubject subject, boolean userCharged, boolean globalCharged) {
        if (userCharged) {
            quotaService.refundDaily(subject, UsageKeys.AI_GENERATIONS, 1);
        }
        if (globalCharged) {
            quotaService.refundGlobalDaily(UsageKeys.AI_SERVERKEY, 1);
        }
    }

    void releaseLock(String lockKey, String lockToken) {
        inflightLock.release(lockKey, lockToken);
    }

    /** Best effort: {@code usage:{id}:ai.questions:d:{day}} += questions. Never throws. */
    void recordQuestions(LimitSubject subject, long questions) {
        if (questions <= 0) {
            return;
        }
        try {
            String key = UsageKeys.daily(idOf(subject), UsageKeys.AI_QUESTIONS,
                    LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
            redis.async().eval(COUNT_SCRIPT, ScriptOutputType.INTEGER, new String[]{key},
                            Long.toString(questions),
                            Long.toString(Math.max(1, quotaProperties.getDailyTtl().toSeconds())))
                    .exceptionally(e -> {
                        log.debug("Could not record ai.questions: {}", e.toString());
                        return null;
                    });
        } catch (RuntimeException e) {
            log.debug("Could not record ai.questions: {}", e.toString());
        }
    }

    /** The id counters and locks are keyed by: the {@code sub}, or {@code ip:{addr}} for a guest. */
    static String idOf(LimitSubject subject) {
        return subject.sub() != null ? subject.sub() : UsageKeys.ipPart(subject.ip());
    }

    private static String currentPath() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest().getRequestURI();
        }
        return "";
    }
}

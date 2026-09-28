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
import com.soulsoftworks.sockbowlquestions.ratelimit.Tier;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link AiGenerationGuard}'s ordering and escape hatches that the
 * Redis-backed {@code AiGenerationLimitsIT} does not reach: auth off (no per-user
 * quota), the limiter switched off (no rate charge, no lock, budget fails open), a
 * lock Redis error (fails closed), and the refund/lock release when a later check
 * rejects.
 */
class AiGenerationGuardTest {

    private static final Instant NOW = Instant.parse("2031-03-10T01:00:00Z");
    private static final LimitSubject AUTHOR = new LimitSubject("author-1", "10.0.0.1", Tier.AUTHOR);
    private static final AiRequestContext SERVER_KEY = AiRequestContext.builder().build();
    private static final AiRequestContext BYO = AiRequestContext.builder().apiKey("sk").model("m").build();

    private RateLimitService rateLimitService;
    private QuotaService quotaService;
    private RateLimitEventRecorder events;
    private RedisCommands<String, String> commands;
    private RateLimitRedis redis;
    private AiSecurityProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        quotaService = mock(QuotaService.class);
        events = mock(RateLimitEventRecorder.class);
        redis = mock(RateLimitRedis.class);
        commands = mock(RedisCommands.class);
        when(redis.sync()).thenReturn(commands);
        when(redis.async()).thenReturn(mock(RedisAsyncCommands.class));
        properties = new AiSecurityProperties();
        properties.getServerKey().setAllowedModels(List.of("server-model"));

        when(rateLimitService.isEnabled()).thenReturn(true);
        when(rateLimitService.tryConsume(eq(AiGenerationGuard.POLICY_AI_GENERATE), any()))
                .thenReturn(Decision.allowed(9, 10));
        when(commands.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");
        when(quotaService.consumeDaily(any(), anyString(), anyLong(), anyBoolean()))
                .thenReturn(new QuotaStatus(UsageKeys.AI_GENERATIONS, 1, 20, NOW));
        when(quotaService.consumeGlobalDaily(anyString(), anyLong(), anyLong(), anyBoolean()))
                .thenReturn(new QuotaStatus(UsageKeys.AI_SERVERKEY, 1, 200, NOW));
    }

    private AiGenerationGuard guard(boolean authEnabled) {
        return new AiGenerationGuard(rateLimitService, quotaService, new QuotaProperties(),
                mock(LimitSubjectResolver.class), events, redis, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), authEnabled);
    }

    @Test
    void serverKeyCallChargesRateLockQuotaAndBudgetInOrder() {
        try (AiPermit permit = guard(true).acquire(AUTHOR, SERVER_KEY)) {
            assertThat(permit.charged()).isTrue();
            permit.success(3);
        }
        verify(rateLimitService).tryConsume(AiGenerationGuard.POLICY_AI_GENERATE, AUTHOR);
        verify(commands).set(eq(UsageKeys.aiInflight("author-1")), anyString(), any(SetArgs.class));
        verify(quotaService).consumeDaily(AUTHOR, UsageKeys.AI_GENERATIONS, 1, false);
        verify(quotaService).consumeGlobalDaily(UsageKeys.AI_SERVERKEY, 200, 1, true);
        verify(quotaService, never()).refundDaily(any(), anyString(), anyLong());
    }

    @Test
    void byoCallIsRateLimitedButNeverTouchesQuotaOrBudget() {
        try (AiPermit permit = guard(true).acquire(AUTHOR, BYO)) {
            assertThat(permit.charged()).isFalse();
            permit.success(1);
        }
        verify(rateLimitService).tryConsume(AiGenerationGuard.POLICY_AI_GENERATE, AUTHOR);
        verifyNoInteractions(quotaService);
    }

    @Test
    void authOffSkipsThePerUserQuotaButKeepsTheBudget() {
        LimitSubject guest = LimitSubject.guest("10.0.0.2");
        try (AiPermit permit = guard(false).acquire(guest, SERVER_KEY)) {
            permit.success(1);
        }
        verify(quotaService, never()).consumeDaily(any(), anyString(), anyLong(), anyBoolean());
        verify(quotaService).consumeGlobalDaily(UsageKeys.AI_SERVERKEY, 200, 1, true);
        verify(commands).set(eq(UsageKeys.aiInflight("ip:10.0.0.2")), anyString(), any(SetArgs.class));
    }

    @Test
    void limiterDisabledSkipsRateAndLockAndTheBudgetFailsOpen() {
        when(rateLimitService.isEnabled()).thenReturn(false);
        try (AiPermit permit = guard(true).acquire(AUTHOR, SERVER_KEY)) {
            permit.success(1);
        }
        verify(rateLimitService, never()).tryConsume(anyString(), any());
        verify(commands, never()).set(anyString(), anyString(), any(SetArgs.class));
        verify(quotaService).consumeGlobalDaily(UsageKeys.AI_SERVERKEY, 200, 1, false);
    }

    @Test
    void rateLimiterUnavailableFailsClosed() {
        when(rateLimitService.tryConsume(eq(AiGenerationGuard.POLICY_AI_GENERATE), any()))
                .thenReturn(Decision.unavailable());
        assertThatThrownBy(() -> guard(true).acquire(AUTHOR, BYO))
                .isInstanceOfSatisfying(LimiterUnavailableException.class,
                        e -> assertThat(e.getPolicy()).isEqualTo(AiGenerationGuard.POLICY_AI_GENERATE));
        verifyNoInteractions(quotaService);
    }

    @Test
    void lockRedisErrorFailsClosed() {
        when(commands.set(anyString(), anyString(), any(SetArgs.class)))
                .thenThrow(new RedisCommandTimeoutException("timeout"));
        assertThatThrownBy(() -> guard(true).acquire(AUTHOR, SERVER_KEY))
                .isInstanceOfSatisfying(LimiterUnavailableException.class,
                        e -> assertThat(e.getPolicy()).isEqualTo(AiGenerationGuard.POLICY_AI_CONCURRENCY));
        verifyNoInteractions(quotaService);
    }

    @Test
    void aHeldLockIsA429WithTheConfiguredRetryAfter() {
        when(commands.set(anyString(), anyString(), any(SetArgs.class))).thenReturn(null);
        assertThatThrownBy(() -> guard(true).acquire(AUTHOR, SERVER_KEY))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.getPolicy()).isEqualTo(AiGenerationGuard.POLICY_AI_CONCURRENCY);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(30);
                });
        verify(events).record(eq(AiGenerationGuard.POLICY_AI_CONCURRENCY), eq(RateLimitEventRecorder.KIND_RATE),
                eq(AUTHOR), anyString());
    }

    @Test
    void aSpentBudgetRefundsTheUserQuotaAndReleasesTheLock() {
        when(quotaService.consumeGlobalDaily(anyString(), anyLong(), anyLong(), anyBoolean()))
                .thenThrow(new QuotaExceededException(UsageKeys.AI_SERVERKEY, 200, 200, NOW));
        assertThatThrownBy(() -> guard(true).acquire(AUTHOR, SERVER_KEY))
                .isInstanceOf(QuotaExceededException.class);
        verify(quotaService).refundDaily(AUTHOR, UsageKeys.AI_GENERATIONS, 1);
        verify(quotaService, never()).refundGlobalDaily(anyString(), anyLong());
        verify(commands).eval(eq(InflightLock.RELEASE_SCRIPT), any(), any(String[].class), anyString());
    }

    @Test
    void aPermitClosedWithoutSuccessRefundsBoth() {
        AiPermit permit = guard(true).acquire(AUTHOR, SERVER_KEY);
        permit.close();
        permit.close();
        verify(quotaService).refundDaily(AUTHOR, UsageKeys.AI_GENERATIONS, 1);
        verify(quotaService).refundGlobalDaily(UsageKeys.AI_SERVERKEY, 1);
    }

    @Test
    void aFailedOpenQuotaReadIsNotRefunded() {
        when(quotaService.consumeDaily(any(), anyString(), anyLong(), anyBoolean()))
                .thenReturn(new QuotaStatus(UsageKeys.AI_GENERATIONS, -1, 20, NOW));
        guard(true).acquire(AUTHOR, SERVER_KEY).close();
        verify(quotaService, never()).refundDaily(any(), anyString(), anyLong());
        verify(quotaService).refundGlobalDaily(UsageKeys.AI_SERVERKEY, 1);
    }

    @Test
    void aDisallowedServerKeyModelIsA400BeforeAnythingIsCharged() {
        AiRequestContext model = AiRequestContext.builder().model("gpt-other").build();
        assertThatThrownBy(() -> guard(true).acquire(AUTHOR, model))
                .isInstanceOf(InvalidApiRequestException.class)
                .hasMessageContaining("gpt-other");
        verifyNoInteractions(rateLimitService, quotaService);

        try (AiPermit permit = guard(true).acquire(AUTHOR,
                AiRequestContext.builder().model("server-model").build())) {
            permit.success(1);
        }
    }
}

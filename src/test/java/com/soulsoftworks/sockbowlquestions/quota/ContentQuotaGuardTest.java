package com.soulsoftworks.sockbowlquestions.quota;

import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitEventRecorder;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlquestions.ratelimit.Tier;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit rules of {@link ContentQuotaGuard} (WP-Q4, WP-FIX-Q), with its collaborators mocked. */
class ContentQuotaGuardTest {

    private final QuotaService quotaService = mock(QuotaService.class);
    private final LimitSubjectResolver resolver = mock(LimitSubjectResolver.class);
    private final PacketRepository packets = mock(PacketRepository.class);
    private final RateLimitEventRecorder recorder = mock(RateLimitEventRecorder.class);
    private final RateLimitRedis redis = mock(RateLimitRedis.class);
    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> redisCommands = mock(RedisCommands.class);

    @BeforeEach
    void setUp() {
        when(quotaService.isEnabled()).thenReturn(true);
        when(resolver.current()).thenReturn(new LimitSubject("kc-1", "10.0.0.1", Tier.AUTHOR));
    }

    private ContentQuotaGuard guard(boolean authEnabled) {
        return new ContentQuotaGuard(quotaService, resolver, packets, recorder, redis, authEnabled);
    }

    /** A guard whose lock spin-waits fast, for the contention/timeout tests below. */
    private ContentQuotaGuard fastLockGuard(Duration spinTimeout, Duration spinInterval) {
        return new ContentQuotaGuard(quotaService, resolver, packets, recorder, redis, true,
                Duration.ofMillis(10_000), spinTimeout, spinInterval);
    }

    @Test
    void authOffOrNoOwnerOrQuotasOffChecksNothing() {
        guard(false).checkPacketsOwned("kc-1");
        assertThat(guard(false).chargeImport("kc-1")).isSameAs(ContentQuotaGuard.ImportCharge.NONE);
        guard(true).checkPacketsOwned(null);
        guard(true).chargeImport(" ");
        when(quotaService.isEnabled()).thenReturn(false);
        guard(true).checkPacketsOwned("kc-1");
        verify(quotaService, never()).effectiveLimitOrUnknown(any(), anyString());
        verify(quotaService, never()).consumeDaily(any(), anyString(), anyLong(), eq(false));
        verifyNoInteractions(packets);
    }

    @Test
    void anUnlimitedTierNeverCountsPackets() {
        when(quotaService.effectiveLimitOrUnknown(any(), eq(UsageKeys.PACKETS_OWNED))).thenReturn(OptionalLong.of(-1L));
        guard(true).checkPacketsOwned("kc-1");
        verifyNoInteractions(packets);
    }

    @Test
    void atTheLimitIsRejectedWithNoResetTimeAndRecorded() {
        when(quotaService.effectiveLimitOrUnknown(any(), eq(UsageKeys.PACKETS_OWNED))).thenReturn(OptionalLong.of(3L));
        when(packets.countByOwnerId("kc-1")).thenReturn(2L, 3L);
        guard(true).checkPacketsOwned("kc-1");

        assertThatThrownBy(() -> guard(true).checkPacketsOwned("kc-1"))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(e -> {
                    QuotaExceededException q = (QuotaExceededException) e;
                    assertThat(q.getMetric()).isEqualTo(UsageKeys.PACKETS_OWNED);
                    assertThat(q.getLimit()).isEqualTo(3);
                    assertThat(q.getUsed()).isEqualTo(3);
                    assertThat(q.getResetsAt()).isNull();
                });
        verify(recorder).record(eq(UsageKeys.PACKETS_OWNED), eq(RateLimitEventRecorder.KIND_QUOTA), any(), any());
    }

    @Test
    void theOwnerNotTheCallerIsChargedWithTheCallersTier() {
        when(quotaService.consumeDaily(any(), eq(UsageKeys.IMPORTS), eq(1L), eq(false)))
                .thenReturn(new QuotaStatus(UsageKeys.IMPORTS, 1, 10, Instant.EPOCH));
        ContentQuotaGuard.ImportCharge charge = guard(true).chargeImport("kc-owner");

        verify(quotaService).consumeDaily(eq(new LimitSubject("kc-owner", "10.0.0.1", Tier.AUTHOR)),
                eq(UsageKeys.IMPORTS), eq(1L), eq(false));
        charge.refund();
        charge.refund();   // idempotent
        verify(quotaService, times(1)).refundDaily(new LimitSubject("kc-owner", "10.0.0.1", Tier.AUTHOR),
                UsageKeys.IMPORTS, 1);
    }

    @Test
    void anUncountedChargeRefundsNothing() {
        // used < 0: SERVICE tier, quotas off at the service, or Redis down (failed open).
        when(quotaService.consumeDaily(any(), eq(UsageKeys.IMPORTS), eq(1L), eq(false)))
                .thenReturn(new QuotaStatus(UsageKeys.IMPORTS, -1, -1, Instant.EPOCH));
        guard(true).chargeImport("kc-1").refund();
        verify(quotaService, never()).refundDaily(any(), anyString(), anyLong());
    }

    @Test
    void anExceededImportIsRecordedAndRethrown() {
        when(quotaService.consumeDaily(any(), eq(UsageKeys.IMPORTS), eq(1L), eq(false)))
                .thenThrow(new QuotaExceededException(UsageKeys.IMPORTS, 10, 10, Instant.EPOCH));
        assertThatThrownBy(() -> guard(true).chargeImport("kc-1")).isInstanceOf(QuotaExceededException.class);
        verify(recorder).record(eq(UsageKeys.IMPORTS), eq(RateLimitEventRecorder.KIND_QUOTA), any(), any());
    }

    /* ------------------------------------------------------------------ */
    /* checkPacketsOwned fails open on an unknown limit (Q-V1-04)          */
    /* ------------------------------------------------------------------ */

    /**
     * A REAL {@link QuotaService} (not the class-level mock), wired with an
     * override-hash lookup that throws, so this proves the actual production
     * path: the {@code hget} failure is caught inside {@code QuotaService} and
     * surfaces to {@link ContentQuotaGuard} as {@link OptionalLong#empty()},
     * never as a silently-wrong tier-default fallback.
     */
    @Test
    void checkPacketsOwnedAllowsTheCreateWhenTheOverrideLookupThrowsEvenIfOwnedExceedsTheTierDefault() {
        QuotaProperties properties = new QuotaProperties();
        properties.setTiers(Map.of(Tier.AUTHOR, Map.of(UsageKeys.PACKETS_OWNED, 5L)));
        RateLimitRedis throwingRedis = mock(RateLimitRedis.class);
        when(throwingRedis.sync()).thenReturn(redisCommands);
        when(redisCommands.hget(anyString(), eq(UsageKeys.PACKETS_OWNED))).thenThrow(new RuntimeException("redis down"));
        QuotaService realQuotaService = new QuotaService(properties, throwingRedis, Clock.systemUTC());

        // Already owns 999, far past the tier default of 5 (e.g. an admin override
        // once raised the real limit higher, and that override is now unreachable).
        when(packets.countByOwnerId("kc-1")).thenReturn(999L);

        new ContentQuotaGuard(realQuotaService, resolver, packets, recorder, redis, true).checkPacketsOwned("kc-1");

        verifyNoInteractions(recorder);
    }

    /* ------------------------------------------------------------------ */
    /* withPacketsOwnedSlot (Q-V1-01)                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void withPacketsOwnedSlotSkipsTheLockWhenNoOwnerAppliesJustLikeCheckPacketsOwned() {
        assertThat(guard(false).withPacketsOwnedSlot("kc-1", () -> "x")).isEqualTo("x");
        assertThat(guard(true).withPacketsOwnedSlot(null, () -> "x")).isEqualTo("x");
        verifyNoInteractions(redis);
    }

    @Test
    void withPacketsOwnedSlotAcquiresTheLockRunsCreateThenReleasesIt() {
        when(redis.sync()).thenReturn(redisCommands);
        when(quotaService.effectiveLimitOrUnknown(any(), eq(UsageKeys.PACKETS_OWNED))).thenReturn(OptionalLong.of(5L));
        when(packets.countByOwnerId("kc-1")).thenReturn(1L);
        when(redisCommands.set(eq("quota:lock:packets-owned:kc-1"), anyString(), any(SetArgs.class)))
                .thenReturn("OK");

        String result = guard(true).withPacketsOwnedSlot("kc-1", () -> "created");

        assertThat(result).isEqualTo("created");
        verify(redisCommands).set(eq("quota:lock:packets-owned:kc-1"), anyString(), any(SetArgs.class));
        // Released by the compare-and-delete script, keyed on the same lock.
        verify(redisCommands).eval(anyString(), any(), any(String[].class), anyString());
    }

    @Test
    void withPacketsOwnedSlotReleasesTheLockEvenWhenTheQuotaIsExceeded() {
        when(redis.sync()).thenReturn(redisCommands);
        when(quotaService.effectiveLimitOrUnknown(any(), eq(UsageKeys.PACKETS_OWNED))).thenReturn(OptionalLong.of(1L));
        when(packets.countByOwnerId("kc-1")).thenReturn(1L);
        when(redisCommands.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");

        assertThatThrownBy(() -> guard(true).withPacketsOwnedSlot("kc-1", () -> "created"))
                .isInstanceOf(QuotaExceededException.class);

        verify(redisCommands).eval(anyString(), any(), any(String[].class), anyString());
    }

    @Test
    void withPacketsOwnedSlotFailsOpenWhenRedisIsUnavailable() {
        when(redis.sync()).thenReturn(redisCommands);
        when(quotaService.effectiveLimitOrUnknown(any(), eq(UsageKeys.PACKETS_OWNED))).thenReturn(OptionalLong.of(5L));
        when(packets.countByOwnerId("kc-1")).thenReturn(1L);
        when(redisCommands.set(anyString(), anyString(), any(SetArgs.class)))
                .thenThrow(new RuntimeException("redis down"));

        String result = guard(true).withPacketsOwnedSlot("kc-1", () -> "created");

        assertThat(result).isEqualTo("created");
        verify(redisCommands, never()).eval(anyString(), any(), any(String[].class), anyString());
    }

    @Test
    void withPacketsOwnedSlotRetriesOnContentionThenSucceeds() {
        when(redis.sync()).thenReturn(redisCommands);
        when(quotaService.effectiveLimitOrUnknown(any(), eq(UsageKeys.PACKETS_OWNED))).thenReturn(OptionalLong.of(5L));
        when(packets.countByOwnerId("kc-1")).thenReturn(1L);
        when(redisCommands.set(anyString(), anyString(), any(SetArgs.class))).thenReturn(null, null, "OK");

        String result = fastLockGuard(Duration.ofSeconds(5), Duration.ofMillis(5))
                .withPacketsOwnedSlot("kc-1", () -> "created");

        assertThat(result).isEqualTo("created");
        verify(redisCommands, times(3)).set(anyString(), anyString(), any(SetArgs.class));
    }

    @Test
    void withPacketsOwnedSlotGivesUpWithRateLimitedAfterTheSpinTimeout() {
        when(redis.sync()).thenReturn(redisCommands);
        when(redisCommands.set(anyString(), anyString(), any(SetArgs.class))).thenReturn(null);

        assertThatThrownBy(() -> fastLockGuard(Duration.ofMillis(40), Duration.ofMillis(10))
                .withPacketsOwnedSlot("kc-1", () -> "created"))
                .isInstanceOf(RateLimitExceededException.class);

        // Never got inside the lock, so the quota was never even checked.
        verifyNoInteractions(packets);
    }
}

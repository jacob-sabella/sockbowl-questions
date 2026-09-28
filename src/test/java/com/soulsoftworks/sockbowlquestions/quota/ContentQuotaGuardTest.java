package com.soulsoftworks.sockbowlquestions.quota;

import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlquestions.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlquestions.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlquestions.ratelimit.RateLimitEventRecorder;
import com.soulsoftworks.sockbowlquestions.ratelimit.Tier;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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

/** Unit rules of {@link ContentQuotaGuard} (WP-Q4), with its collaborators mocked. */
class ContentQuotaGuardTest {

    private final QuotaService quotaService = mock(QuotaService.class);
    private final LimitSubjectResolver resolver = mock(LimitSubjectResolver.class);
    private final PacketRepository packets = mock(PacketRepository.class);
    private final RateLimitEventRecorder recorder = mock(RateLimitEventRecorder.class);

    @BeforeEach
    void setUp() {
        when(quotaService.isEnabled()).thenReturn(true);
        when(resolver.current()).thenReturn(new LimitSubject("kc-1", "10.0.0.1", Tier.AUTHOR));
    }

    private ContentQuotaGuard guard(boolean authEnabled) {
        return new ContentQuotaGuard(quotaService, resolver, packets, recorder, authEnabled);
    }

    @Test
    void authOffOrNoOwnerOrQuotasOffChecksNothing() {
        guard(false).checkPacketsOwned("kc-1");
        assertThat(guard(false).chargeImport("kc-1")).isSameAs(ContentQuotaGuard.ImportCharge.NONE);
        guard(true).checkPacketsOwned(null);
        guard(true).chargeImport(" ");
        when(quotaService.isEnabled()).thenReturn(false);
        guard(true).checkPacketsOwned("kc-1");
        verify(quotaService, never()).effectiveLimit(any(), anyString());
        verify(quotaService, never()).consumeDaily(any(), anyString(), anyLong(), eq(false));
        verifyNoInteractions(packets);
    }

    @Test
    void anUnlimitedTierNeverCountsPackets() {
        when(quotaService.effectiveLimit(any(), eq(UsageKeys.PACKETS_OWNED))).thenReturn(-1L);
        guard(true).checkPacketsOwned("kc-1");
        verifyNoInteractions(packets);
    }

    @Test
    void atTheLimitIsRejectedWithNoResetTimeAndRecorded() {
        when(quotaService.effectiveLimit(any(), eq(UsageKeys.PACKETS_OWNED))).thenReturn(3L);
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
}

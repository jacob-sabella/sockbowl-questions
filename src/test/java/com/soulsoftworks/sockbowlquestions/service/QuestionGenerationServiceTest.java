package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.ai.AiGenerationGuard;
import com.soulsoftworks.sockbowlquestions.ai.AiPermit;
import com.soulsoftworks.sockbowlquestions.config.AiSecurityProperties;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.quota.ContentQuotaGuard;
import com.soulsoftworks.sockbowlquestions.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.service.strategy.QuestionGenerationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit rules of {@link QuestionGenerationService#generatePacket} (WP-FIX3-Q item 1;
 * plan m4-fix3): the {@code packets-owned} quota is pre-checked, unlocked, BEFORE any
 * AI cost is charged, and the packets-owned lock is held only around the final save,
 * never across the AI generation itself.
 */
class QuestionGenerationServiceTest {

    private final QuestionGenerationStrategy strategy = mock(QuestionGenerationStrategy.class);
    private final AiGenerationGuard aiGenerationGuard = mock(AiGenerationGuard.class);
    private final ContentQuotaGuard contentQuotaGuard = mock(ContentQuotaGuard.class);
    private final PacketRepository packetRepository = mock(PacketRepository.class);
    private final AiPermit permit = mock(AiPermit.class);

    private QuestionGenerationService service;

    @BeforeEach
    void setUp() {
        when(strategy.getStrategyName()).thenReturn("default");
        service = new QuestionGenerationService(strategy, aiGenerationGuard, new AiSecurityProperties(),
                contentQuotaGuard, packetRepository);
    }

    /**
     * The core FIX3-Q acceptance test: a caller already at the {@code packets-owned}
     * limit gets 429 {@code quota_exceeded} without ever reaching the AI permit (no
     * ai-generate rate token, no concurrency lock, no ai.generations quota charge)
     * or the strategy/repository.
     */
    @Test
    void atTheOwnedLimitBurnsNoAiGenerateTokenAndGetsQuotaExceeded() throws Exception {
        doThrow(new QuotaExceededException("packets-owned", 5, 5, null))
                .when(contentQuotaGuard).checkPacketsOwned("owner-1");

        assertThatThrownBy(() -> service.generatePacket("topic", null, 1, false,
                AiRequestContext.builder().build(), "owner-1", "owner"))
                .isInstanceOf(QuotaExceededException.class);

        verifyNoInteractions(aiGenerationGuard);
        verify(strategy, never()).generatePacket(any(), any(), anyInt(), anyBoolean(), any(), any(), any());
        verifyNoInteractions(packetRepository);
    }

    /**
     * Normal flow: the pre-check passes, the AI permit is acquired, the strategy
     * generates OUTSIDE the packets-owned lock, and only the save runs inside it.
     * Proves the ordering the FIX3-Q design requires, not just the outcome.
     */
    @Test
    void generatesOutsideTheLockThenSavesInsideItAndMarksThePermitSuccessful() throws Exception {
        Packet built = Packet.builder().name("built").ownerId("owner-1").build();
        Packet saved = Packet.builder().name("built").ownerId("owner-1").build();
        AtomicBoolean generatedBeforeLock = new AtomicBoolean(false);

        when(aiGenerationGuard.acquire(any())).thenReturn(permit);
        when(strategy.generatePacket(eq("topic"), isNull(), eq(2), eq(false), any(), eq("owner-1"), eq("owner")))
                .thenAnswer(inv -> {
                    // The (simulated) AI work finishes with the packets-owned lock not yet taken.
                    verifyNoInteractions(packetRepository);
                    generatedBeforeLock.set(true);
                    return built;
                });
        when(contentQuotaGuard.withPacketsOwnedSlot(eq("owner-1"), any())).thenAnswer(inv -> {
            assertThat(generatedBeforeLock).as("the AI call must finish before the packets-owned lock is taken").isTrue();
            Supplier<Packet> save = inv.getArgument(1);
            return save.get();
        });
        when(packetRepository.save(built)).thenReturn(saved);

        Packet result = service.generatePacket("topic", null, 2, false,
                AiRequestContext.builder().build(), "owner-1", "owner");

        assertThat(result).isSameAs(saved);
        InOrder order = inOrder(contentQuotaGuard, aiGenerationGuard, strategy, packetRepository, permit);
        order.verify(contentQuotaGuard).checkPacketsOwned("owner-1");
        order.verify(aiGenerationGuard).acquire(any());
        order.verify(strategy).generatePacket(any(), any(), anyInt(), anyBoolean(), any(), any(), any());
        order.verify(contentQuotaGuard).withPacketsOwnedSlot(eq("owner-1"), any());
        order.verify(packetRepository).save(built);
        order.verify(permit).success(2L);
    }

    /**
     * A slow generation cannot overshoot the quota: even though the unlocked
     * pre-check passed, a concurrent caller may have filled the quota while this
     * one was generating. The re-check inside {@code withPacketsOwnedSlot} at save
     * time still rejects it, and the AI permit is never marked successful, so its
     * {@code ai-generate} charge is refunded on {@code close()}.
     */
    @Test
    void aRaceThatFillsTheQuotaDuringGenerationStillRejectsTheSaveAndRefundsTheAiCharge() throws Exception {
        Packet built = Packet.builder().name("built").ownerId("owner-1").build();
        when(aiGenerationGuard.acquire(any())).thenReturn(permit);
        when(strategy.generatePacket(any(), any(), anyInt(), anyBoolean(), any(), any(), any())).thenReturn(built);
        when(contentQuotaGuard.withPacketsOwnedSlot(eq("owner-1"), any()))
                .thenThrow(new QuotaExceededException("packets-owned", 2, 2, null));

        assertThatThrownBy(() -> service.generatePacket("topic", null, 1, false,
                AiRequestContext.builder().build(), "owner-1", "owner"))
                .isInstanceOf(QuotaExceededException.class);

        verify(permit, never()).success(anyLong());
        verify(permit).close();
        verifyNoInteractions(packetRepository);
    }
}

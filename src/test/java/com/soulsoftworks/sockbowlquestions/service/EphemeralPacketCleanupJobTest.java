package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.config.EphemeralPacketProperties;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D15 TTL cleanup: the cutoff it asks for, batching, and the {@code ephemeral-ttl} binding. */
class EphemeralPacketCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private final PacketRepository repo = mock(PacketRepository.class);
    private final EphemeralPacketProperties props = new EphemeralPacketProperties();
    private final EphemeralPacketCleanupJob job =
            new EphemeralPacketCleanupJob(repo, props, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void defaultTtlIs24Hours() {
        assertThat(props.getEphemeralTtl()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void deletesEverythingOlderThanTheTtl() {
        long cutoff = NOW.minus(Duration.ofHours(24)).toEpochMilli();
        when(repo.findExpiredEphemeralPacketIds(cutoff, 200)).thenReturn(List.of("a", "b"));

        assertThat(job.purgeExpired()).isEqualTo(2);

        verify(repo).deletePacketCascade("a");
        verify(repo).deletePacketCascade("b");
        verify(repo, times(1)).findExpiredEphemeralPacketIds(anyLong(), anyInt());
    }

    @Test
    void honorsAConfiguredTtl() {
        props.setEphemeralTtl(Duration.ofMinutes(90));
        when(repo.findExpiredEphemeralPacketIds(anyLong(), anyInt())).thenReturn(List.of());

        assertThat(job.purgeExpired()).isZero();

        verify(repo).findExpiredEphemeralPacketIds(eq(NOW.minus(Duration.ofMinutes(90)).toEpochMilli()), eq(200));
        verify(repo, never()).deletePacketCascade(anyString());
    }

    @Test
    void loopsWhileBatchesComeBackFull() {
        props.setEphemeralCleanupBatchSize(2);
        when(repo.findExpiredEphemeralPacketIds(anyLong(), eq(2)))
                .thenReturn(List.of("a", "b"), List.of("c", "d"), List.of("e"));

        assertThat(job.purgeExpired()).isEqualTo(5);

        verify(repo, times(3)).findExpiredEphemeralPacketIds(anyLong(), eq(2));
        verify(repo, times(5)).deletePacketCascade(anyString());
    }

    @Test
    void negativeTtlIsRejected() {
        props.setEphemeralTtl(Duration.ofHours(-1));
        assertThatThrownBy(job::purgeExpired).isInstanceOf(IllegalStateException.class);
        verify(repo, never()).deletePacketCascade(anyString());
    }

    @Test
    void scheduledRunSwallowsFailuresSoTheScheduleSurvives() {
        doThrow(new IllegalStateException("neo4j down")).when(repo).findExpiredEphemeralPacketIds(anyLong(), anyInt());

        job.scheduledPurge(); // no exception
    }

    @Test
    void ttlBindsFromSockbowlPacketEphemeralTtl() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(EphemeralPacketProperties.class)
                .withPropertyValues("sockbowl.packet.ephemeral-ttl=90m")
                .run(ctx -> assertThat(ctx.getBean(EphemeralPacketProperties.class).getEphemeralTtl())
                        .isEqualTo(Duration.ofMinutes(90)));
    }
}

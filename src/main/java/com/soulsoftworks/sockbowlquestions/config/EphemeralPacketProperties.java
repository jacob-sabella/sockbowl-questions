package com.soulsoftworks.sockbowlquestions.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Lifetime of game-only EPHEMERAL packets (D15), bound from {@code sockbowl.packet.*}.
 * The cleanup schedule itself is {@code sockbowl.packet.ephemeral-cleanup.interval}
 * and {@code initial-delay} (read by {@code EphemeralPacketCleanupJob}'s {@code @Scheduled}).
 */
@Configuration
@ConfigurationProperties(prefix = "sockbowl.packet")
@Data
public class EphemeralPacketProperties {

    /** How long an EPHEMERAL packet lives before the cleanup job deletes it. Default 24h. */
    private Duration ephemeralTtl = Duration.ofHours(24);

    /** At most this many packets are deleted per query round; the job loops until done. */
    private int ephemeralCleanupBatchSize = 200;
}

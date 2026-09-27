package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.config.EphemeralPacketProperties;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Deletes game-only EPHEMERAL packets (D15) once they are older than
 * {@code sockbowl.packet.ephemeral-ttl} (default 24h), with the question nodes they
 * own. Runs every {@code sockbowl.packet.ephemeral-cleanup.interval} (default 1h),
 * first after {@code initial-delay} (default 5m). A game that loaded the packet keeps
 * its own copy, so deleting it doesn't affect a match in progress.
 *
 * <p>Disable with {@code sockbowl.packet.ephemeral-cleanup.enabled=false}. Scheduling
 * is switched on by {@link com.soulsoftworks.sockbowlquestions.config.SchedulingConfig}
 * under the same property.
 */
@Component
@ConditionalOnProperty(name = "sockbowl.packet.ephemeral-cleanup.enabled", havingValue = "true",
        matchIfMissing = true)
public class EphemeralPacketCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(EphemeralPacketCleanupJob.class);

    /** Upper bound on query rounds per run, so a bad batch can't loop forever. */
    private static final int MAX_ROUNDS = 1000;

    private final PacketRepository packetRepository;
    private final EphemeralPacketProperties properties;
    private final Clock clock;

    @Autowired
    public EphemeralPacketCleanupJob(PacketRepository packetRepository, EphemeralPacketProperties properties) {
        this(packetRepository, properties, Clock.systemUTC());
    }

    /** For tests: a fixed or offset clock stands in for "now". */
    public EphemeralPacketCleanupJob(PacketRepository packetRepository, EphemeralPacketProperties properties,
                                     Clock clock) {
        this.packetRepository = packetRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sockbowl.packet.ephemeral-cleanup.interval:PT1H}",
            initialDelayString = "${sockbowl.packet.ephemeral-cleanup.initial-delay:PT5M}")
    public void scheduledPurge() {
        try {
            purgeExpired();
        } catch (RuntimeException e) {
            // Keep the schedule alive; the next run retries.
            log.warn("EPHEMERAL packet cleanup failed", e);
        }
    }

    /**
     * Deletes every EPHEMERAL packet created more than the TTL before now.
     *
     * @return how many packets were deleted
     */
    public int purgeExpired() {
        Duration ttl = properties.getEphemeralTtl();
        if (ttl == null || ttl.isNegative()) {
            throw new IllegalStateException("sockbowl.packet.ephemeral-ttl must be a non-negative duration");
        }
        int batchSize = Math.max(1, properties.getEphemeralCleanupBatchSize());
        long cutoff = clock.instant().minus(ttl).toEpochMilli();
        int deleted = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<String> ids = packetRepository.findExpiredEphemeralPacketIds(cutoff, batchSize);
            for (String id : ids) {
                packetRepository.deletePacketCascade(id);
            }
            deleted += ids.size();
            if (ids.size() < batchSize) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} EPHEMERAL packet(s) older than {}", deleted, ttl);
        }
        return deleted;
    }
}

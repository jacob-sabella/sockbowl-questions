package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One-time, idempotent D2 backfill (plan P2): every {@code Packet} node without a
 * {@code visibility} gets {@code PUBLISHED}, so legacy bank packets stay usable by
 * guests once reads are visibility-aware. It only fills in missing values; it never
 * changes a visibility that is already set or deletes anything, so it is safe to run
 * on every start against real data. Read logic also treats a missing visibility as
 * PUBLISHED, so this is belt-and-braces.
 *
 * <p>Disable with {@code sockbowl.migrations.packet-visibility.enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sockbowl.migrations.packet-visibility.enabled", havingValue = "true",
        matchIfMissing = true)
public class PacketVisibilityMigration implements ApplicationRunner {

    private final PacketRepository packetRepository;

    public PacketVisibilityMigration(PacketRepository packetRepository) {
        this.packetRepository = packetRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (RuntimeException e) {
            // Never block startup on the backfill: reads already treat a missing
            // visibility as PUBLISHED, and the next start retries.
            log.warn("Packet visibility migration failed; legacy packets are still read as PUBLISHED", e);
        }
    }

    /** Runs the backfill and returns how many packets it updated. */
    public long migrate() {
        PacketVisibility legacy = PacketVisibility.effective(null);
        long updated = packetRepository.backfillMissingVisibility(legacy.name());
        if (updated > 0) {
            log.info("Packet visibility migration: set visibility={} on {} legacy packet(s)", legacy, updated);
        } else {
            log.info("Packet visibility migration: no legacy packets to update");
        }
        return updated;
    }
}

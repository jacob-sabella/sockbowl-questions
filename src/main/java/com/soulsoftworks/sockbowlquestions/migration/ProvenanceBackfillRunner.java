package com.soulsoftworks.sockbowlquestions.migration;

import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One-time, idempotent D13 backfill (M4-PV-01): every {@code Packet} node saved
 * before provenance tracking shipped has no {@code createdBy}. For packets with a
 * recorded {@code ownerId} this attributes {@code createdBy}/{@code lastModifiedBy}
 * to that owner and fills in {@code createdAt}/{@code lastModifiedAt} if unset;
 * ownerless legacy packets are left alone, since there is no signal to attribute
 * them to (see {@link PacketRepository#backfillProvenanceFromOwner()}).
 *
 * <p>This only ever fills in a missing {@code createdBy}; it never overwrites one
 * that's already set and never touches {@code ownerId}, so it is safe to run on
 * every start against real data, and a second run is a no-op.
 *
 * <p>Disable with {@code sockbowl.migrations.provenance-backfill.enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sockbowl.migrations.provenance-backfill.enabled", havingValue = "true",
        matchIfMissing = true)
public class ProvenanceBackfillRunner implements ApplicationRunner {

    private final PacketRepository packetRepository;

    public ProvenanceBackfillRunner(PacketRepository packetRepository) {
        this.packetRepository = packetRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (RuntimeException e) {
            // Never block startup on the backfill: it only affects the provenance
            // fields' visibility to callers, not the packet's usability, and the
            // next start retries.
            log.warn("Provenance backfill failed; legacy owned packets are still missing createdBy", e);
        }
    }

    /** Runs the backfill and returns how many packets it updated. */
    public long migrate() {
        long updated = packetRepository.backfillProvenanceFromOwner();
        if (updated > 0) {
            log.info("Provenance backfill: set createdBy/lastModifiedBy from ownerId on {} legacy packet(s)", updated);
        } else {
            log.info("Provenance backfill: no legacy owned packets to update");
        }
        return updated;
    }
}

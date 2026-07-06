package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.client.QbreaderClient;
import com.soulsoftworks.sockbowlquestions.client.dto.QbBonus;
import com.soulsoftworks.sockbowlquestions.client.dto.QbPacketResponse;
import com.soulsoftworks.sockbowlquestions.client.dto.QbRandomFilter;
import com.soulsoftworks.sockbowlquestions.client.dto.QbTossup;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns qbreader.org content into native sockbowl {@link Packet}s.
 *
 * <p>Fetches from {@link QbreaderClient}, shapes the questions into plain maps,
 * and persists the entire packet — difficulty, tossups, bonuses, bonus parts,
 * and the taxonomy each references — in a single Cypher write via
 * {@link PacketRepository#batchCreatePacket}. This replaces ~40 sequential
 * authoring calls with one round trip.
 */
@Service
public class QbreaderImportService {

    private static final Logger log = LoggerFactory.getLogger(QbreaderImportService.class);

    private final QbreaderClient qbreader;
    private final PacketRepository packetRepository;

    public QbreaderImportService(QbreaderClient qbreader, PacketRepository packetRepository) {
        this.qbreader = qbreader;
        this.packetRepository = packetRepository;
    }

    /**
     * Import one published packet from a qbreader set. Idempotent: re-importing
     * the same set/packet returns the already-imported Packet.
     */
    public ImportOutcome importPacket(String setName, int packetNumber) {
        String packetName = setName + " — Packet " + packetNumber;

        Packet existing = findByExactName(packetName);
        if (existing != null) {
            log.info("qbreader packet '{}' already imported (id={}), returning existing", packetName, existing.getId());
            return new ImportOutcome(existing, List.of());
        }

        QbPacketResponse qb = qbreader.packet(setName, packetNumber);
        if (qb == null || qb.tossups() == null || qb.tossups().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "qbreader returned no questions for '" + setName + "' packet " + packetNumber);
        }
        Packet packet = assemble(packetName, firstDifficulty(qb.tossups()), qb.tossups(), qb.bonuses());
        return new ImportOutcome(packet, collectRemoteIds(qb.tossups(), qb.bonuses()));
    }

    /**
     * Build a fresh packet from random qbreader questions matching the given
     * filters, avoiding any question whose qbreader id is in {@code excludeRemoteIds}
     * (over-fetches a buffer then filters, so repeats are rare rather than impossible).
     *
     * @return the created packet plus the qbreader ids of the questions actually used,
     *         so the caller can record them against a user for future de-duplication.
     */
    public ImportOutcome importRandomPacket(QbRandomFilter filter, int tossupCount, int bonusCount,
                                            String name, Collection<String> excludeRemoteIds) {
        Set<String> exclude = excludeRemoteIds == null ? Set.of() : new HashSet<>(excludeRemoteIds);

        List<QbTossup> tossups = fetchFreshTossups(filter, Math.max(1, tossupCount), exclude);
        List<QbBonus> bonuses = fetchFreshBonuses(filter, Math.max(0, bonusCount), exclude);
        if (tossups.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "qbreader returned no tossups for the requested filters");
        }

        String packetName = (name == null || name.isBlank()) ? "Custom qbreader packet" : name.trim();
        Integer diff = (filter == null || filter.difficulties() == null || filter.difficulties().isEmpty())
                ? firstDifficulty(tossups) : filter.difficulties().get(0);
        Packet packet = assemble(uniqueName(packetName), diff, tossups, bonuses);
        return new ImportOutcome(packet, collectRemoteIds(tossups, bonuses));
    }

    /** A completed import: the packet and the qbreader ids of the questions it used. */
    public record ImportOutcome(Packet packet, List<String> usedRemoteIds) {}

    /* -------------------- random fetch + de-dupe ------------------------- */

    /** Over-fetch a buffer proportional to the exclusion set, then take the first N fresh, distinct tossups. */
    private List<QbTossup> fetchFreshTossups(QbRandomFilter filter, int count, Set<String> exclude) {
        if (count <= 0) {
            return List.of();
        }
        int fetch = Math.min(count + Math.min(exclude.size(), 30) + 5, 60);
        QbPacketResponse resp = qbreader.randomTossups(filter, fetch);
        List<QbTossup> all = (resp == null || resp.tossups() == null) ? List.of() : resp.tossups();
        List<QbTossup> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (QbTossup t : all) {
            String rid = t.remoteId();
            if (rid != null && (exclude.contains(rid) || !seen.add(rid))) {
                continue;
            }
            fresh.add(t);
            if (fresh.size() >= count) {
                break;
            }
        }
        return fresh;
    }

    private List<QbBonus> fetchFreshBonuses(QbRandomFilter filter, int count, Set<String> exclude) {
        if (count <= 0) {
            return List.of();
        }
        int fetch = Math.min(count + Math.min(exclude.size(), 30) + 5, 60);
        QbPacketResponse resp = qbreader.randomBonuses(filter, fetch);
        List<QbBonus> all = (resp == null || resp.bonuses() == null) ? List.of() : resp.bonuses();
        List<QbBonus> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (QbBonus b : all) {
            String rid = b.remoteId();
            if (rid != null && (exclude.contains(rid) || !seen.add(rid))) {
                continue;
            }
            fresh.add(b);
            if (fresh.size() >= count) {
                break;
            }
        }
        return fresh;
    }

    /** The distinct, non-null qbreader ids across the given tossups and bonuses. */
    private static List<String> collectRemoteIds(List<QbTossup> tossups, List<QbBonus> bonuses) {
        Set<String> ids = new LinkedHashSet<>();
        if (tossups != null) {
            for (QbTossup t : tossups) {
                if (t.remoteId() != null && !t.remoteId().isBlank()) {
                    ids.add(t.remoteId());
                }
            }
        }
        if (bonuses != null) {
            for (QbBonus b : bonuses) {
                if (b.remoteId() != null && !b.remoteId().isBlank()) {
                    ids.add(b.remoteId());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /* -------------------------------------------------------------------- */

    private Packet assemble(String packetName, Integer packetDifficulty,
                            List<QbTossup> tossups, List<QbBonus> bonuses) {
        List<Map<String, Object>> tossupRows = new ArrayList<>();
        int order = 0;
        for (QbTossup t : tossups) {
            String category = blankTo(t.category(), "Miscellaneous");
            tossupRows.add(Map.of(
                    "question", clean(t.question()),
                    "answer", clean(t.answer()),
                    "category", category,
                    "subcategory", blankTo(t.subcategory(), category),
                    "remoteId", clean(t.remoteId()),
                    "order", order++));
        }

        List<Map<String, Object>> bonusRows = new ArrayList<>();
        order = 0;
        if (bonuses != null) {
            for (QbBonus b : bonuses) {
                if (b.parts() == null || b.parts().isEmpty()) {
                    continue;
                }
                String category = blankTo(b.category(), "Miscellaneous");
                List<Map<String, Object>> parts = new ArrayList<>();
                for (int i = 0; i < b.parts().size(); i++) {
                    String answer = (b.answers() != null && i < b.answers().size()) ? b.answers().get(i) : "";
                    parts.add(Map.of(
                            "question", clean(b.parts().get(i)),
                            "answer", clean(answer),
                            "order", i));
                }
                bonusRows.add(Map.of(
                        "preamble", clean(b.leadin()),
                        "category", category,
                        "subcategory", blankTo(b.subcategory(), category),
                        "remoteId", clean(b.remoteId()),
                        "order", order++,
                        "parts", parts));
            }
        }

        String id = packetRepository.batchCreatePacket(
                packetName, difficultyLabel(packetDifficulty), tossupRows, bonusRows);
        log.info("Imported qbreader packet '{}' (id={}, {} tossups, {} bonuses)",
                packetName, id, tossupRows.size(), bonusRows.size());
        return Packet.builder().id(id).name(packetName).build();
    }

    private Packet findByExactName(String name) {
        return packetRepository.searchByName(name).stream()
                .filter(p -> name.equalsIgnoreCase(p.getName()))
                .findFirst().orElse(null);
    }

    /** Appends a numeric suffix if a packet with this name already exists. */
    private String uniqueName(String base) {
        if (findByExactName(base) == null) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " (" + i + ")";
            if (findByExactName(candidate) == null) {
                return candidate;
            }
        }
        return base + " (" + System.identityHashCode(base) + ")";
    }

    private static Integer firstDifficulty(List<QbTossup> tossups) {
        return tossups.isEmpty() ? null : tossups.get(0).difficulty();
    }

    /** Maps qbreader's 1-10 difficulty scale to a human-readable sockbowl difficulty. */
    private static String difficultyLabel(Integer d) {
        if (d == null) return "Regular High School";
        if (d <= 2) return "Middle School";
        if (d <= 4) return "Easy High School";
        if (d == 5) return "Regular High School";
        if (d == 6) return "Hard High School";
        if (d <= 8) return "College";
        return "Open";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

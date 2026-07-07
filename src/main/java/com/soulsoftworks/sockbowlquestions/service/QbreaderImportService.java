package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.client.dto.QbRandomFilter;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.BankRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Builds native sockbowl {@link Packet}s from the LOCAL Neo4j question bank
 * ({@code :BankTossup} / {@code :BankBonus} nodes loaded from the qbreader dump).
 * There is no remote qbreader call — {@link BankRepository} samples the bank with
 * strict, complete filters, and the sampled questions are COPIED into fresh,
 * packet-owned nodes via {@link PacketRepository#batchCreatePacket} (the same proven
 * write path used for authored packets), so a generated packet behaves exactly like
 * an authored one for the game, editing, and deletion.
 */
@Service
public class QbreaderImportService {

    private static final Logger log = LoggerFactory.getLogger(QbreaderImportService.class);

    private final BankRepository bankRepository;
    private final PacketRepository packetRepository;

    public QbreaderImportService(BankRepository bankRepository, PacketRepository packetRepository) {
        this.bankRepository = bankRepository;
        this.packetRepository = packetRepository;
    }

    /**
     * Build a fresh packet from bank questions matching the given filters, excluding
     * any question whose qbreader id is in {@code excludeRemoteIds}.
     *
     * @return the created packet plus the qbreader ids of the questions actually used,
     *         so the caller can record them against a user for future de-duplication.
     */
    public ImportOutcome importRandomPacket(QbRandomFilter filter, int tossupCount, int bonusCount,
                                            String name, Collection<String> excludeRemoteIds) {
        List<String> exclude = excludeRemoteIds == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(excludeRemoteIds));

        List<Map<String, Object>> tossups = bankRepository.sampleBankTossups(
                emptyToNull(filter == null ? null : filter.categories()),
                emptyToNull(filter == null ? null : filter.subcategories()),
                emptyToNull(filter == null ? null : filter.alternateSubcategories()),
                emptyToNull(filter == null ? null : filter.difficulties()),
                filter == null ? null : filter.minYear(),
                filter == null ? null : filter.maxYear(),
                filter != null && Boolean.TRUE.equals(filter.standardOnly()),
                exclude, Math.max(1, tossupCount));

        List<Map<String, Object>> bonuses = bankRepository.sampleBankBonuses(
                emptyToNull(filter == null ? null : filter.categories()),
                emptyToNull(filter == null ? null : filter.subcategories()),
                emptyToNull(filter == null ? null : filter.alternateSubcategories()),
                emptyToNull(filter == null ? null : filter.difficulties()),
                filter == null ? null : filter.minYear(),
                filter == null ? null : filter.maxYear(),
                filter != null && Boolean.TRUE.equals(filter.standardOnly()),
                exclude, Math.max(0, bonusCount));

        if (tossups.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No bank questions matched the requested filters");
        }

        List<String> usedRemoteIds = new ArrayList<>();
        List<Map<String, Object>> tossupRows = new ArrayList<>();
        int order = 0;
        for (Map<String, Object> t : tossups) {
            String remoteId = clean(str(t.get("remoteId")));
            tossupRows.add(Map.of(
                    "question", clean(str(t.get("question"))),
                    "answer", clean(str(t.get("answer"))),
                    "category", str(t.get("category")),
                    "subcategory", str(t.get("subcategory")),
                    "remoteId", remoteId,
                    "order", order++));
            if (!remoteId.isBlank()) usedRemoteIds.add(remoteId);
        }

        List<Map<String, Object>> bonusRows = new ArrayList<>();
        order = 0;
        for (Map<String, Object> b : bonuses) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> partsIn = (List<Map<String, Object>>) b.get("parts");
            if (partsIn == null || partsIn.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> parts = new ArrayList<>();
            int i = 0;
            for (Map<String, Object> p : partsIn) {
                parts.add(Map.of(
                        "question", clean(str(p.get("question"))),
                        "answer", clean(str(p.get("answer"))),
                        "order", i++));
            }
            String remoteId = clean(str(b.get("remoteId")));
            bonusRows.add(Map.of(
                    "preamble", clean(str(b.get("preamble"))),
                    "category", str(b.get("category")),
                    "subcategory", str(b.get("subcategory")),
                    "remoteId", remoteId,
                    "order", order++,
                    "parts", parts));
            if (!remoteId.isBlank()) usedRemoteIds.add(remoteId);
        }

        Integer diff = (filter == null || filter.difficulties() == null || filter.difficulties().isEmpty())
                ? null : filter.difficulties().get(0);
        String packetName = uniqueName((name == null || name.isBlank()) ? "Custom packet" : name.trim());

        String id = packetRepository.batchCreatePacket(
                packetName, difficultyLabel(diff), tossupRows, bonusRows);
        log.info("Generated local packet '{}' (id={}, {} tossups, {} bonuses)",
                packetName, id, tossupRows.size(), bonusRows.size());
        return new ImportOutcome(
                Packet.builder().id(id).name(packetName).build(),
                new ArrayList<>(new LinkedHashSet<>(usedRemoteIds)));
    }

    /** A completed generation: the packet and the qbreader ids of the questions it used. */
    public record ImportOutcome(Packet packet, List<String> usedRemoteIds) {}

    /* -------------------------------------------------------------------- */

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

    private static <T> List<T> emptyToNull(List<T> list) {
        return (list == null || list.isEmpty()) ? null : list;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

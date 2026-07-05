package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.client.QbreaderClient;
import com.soulsoftworks.sockbowlquestions.client.dto.QbBonus;
import com.soulsoftworks.sockbowlquestions.client.dto.QbPacketResponse;
import com.soulsoftworks.sockbowlquestions.client.dto.QbTossup;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    public Packet importPacket(String setName, int packetNumber) {
        String packetName = setName + " — Packet " + packetNumber;

        Packet existing = findByExactName(packetName);
        if (existing != null) {
            log.info("qbreader packet '{}' already imported (id={}), returning existing", packetName, existing.getId());
            return existing;
        }

        QbPacketResponse qb = qbreader.packet(setName, packetNumber);
        if (qb == null || qb.tossups() == null || qb.tossups().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "qbreader returned no questions for '" + setName + "' packet " + packetNumber);
        }
        return assemble(packetName, firstDifficulty(qb.tossups()), qb.tossups(), qb.bonuses());
    }

    /**
     * Build a fresh packet from random qbreader questions matching the given
     * filters. categories are qbreader category names; difficulties are the
     * qbreader 1-10 scale.
     */
    public Packet importRandomPacket(List<String> categories, List<Integer> difficulties,
                                     int tossupCount, int bonusCount, String name) {
        QbPacketResponse tos = qbreader.randomTossups(categories, difficulties, Math.max(1, tossupCount));
        QbPacketResponse bon = qbreader.randomBonuses(categories, difficulties, Math.max(0, bonusCount));
        List<QbTossup> tossups = tos == null ? List.of() : tos.tossups();
        List<QbBonus> bonuses = bon == null ? List.of() : bon.bonuses();
        if (tossups == null || tossups.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "qbreader returned no tossups for the requested filters");
        }
        String packetName = (name == null || name.isBlank()) ? "Custom qbreader packet" : name.trim();
        Integer diff = (difficulties == null || difficulties.isEmpty())
                ? firstDifficulty(tossups) : difficulties.get(0);
        return assemble(uniqueName(packetName), diff, tossups, bonuses);
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

package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.api.input.BonusInput;
import com.soulsoftworks.sockbowlquestions.api.input.BonusPartInput;
import com.soulsoftworks.sockbowlquestions.api.input.CreatePacketInput;
import com.soulsoftworks.sockbowlquestions.api.input.TossupInput;
import com.soulsoftworks.sockbowlquestions.client.QbreaderClient;
import com.soulsoftworks.sockbowlquestions.client.dto.QbBonus;
import com.soulsoftworks.sockbowlquestions.client.dto.QbPacketResponse;
import com.soulsoftworks.sockbowlquestions.client.dto.QbTossup;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns qbreader.org content into native sockbowl {@link Packet}s.
 *
 * <p>This is a thin adapter over {@link PacketAuthoringService}: it fetches from
 * {@link QbreaderClient}, find-or-creates the Category/Subcategory/Difficulty
 * nodes each question references, then delegates all persistence and ordering to
 * the authoring service (calling the service layer directly, so the GraphQL
 * layer's {@code @PreAuthorize} gates do not apply to internal imports).
 */
@Service
public class QbreaderImportService {

    private static final Logger log = LoggerFactory.getLogger(QbreaderImportService.class);

    private final QbreaderClient qbreader;
    private final PacketAuthoringService authoring;
    private final PacketRepository packetRepository;
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final DifficultyRepository difficultyRepository;

    public QbreaderImportService(QbreaderClient qbreader,
                                 PacketAuthoringService authoring,
                                 PacketRepository packetRepository,
                                 CategoryRepository categoryRepository,
                                 SubcategoryRepository subcategoryRepository,
                                 DifficultyRepository difficultyRepository) {
        this.qbreader = qbreader;
        this.authoring = authoring;
        this.packetRepository = packetRepository;
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.difficultyRepository = difficultyRepository;
    }

    /**
     * Import one published packet from a qbreader set. Idempotent: re-importing
     * the same set/packet returns the already-imported Packet instead of
     * creating a duplicate.
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
        Integer diff = qb.tossups().get(0).difficulty();
        return assemble(packetName, diff, qb.tossups(), qb.bonuses());
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
        String packetName = (name == null || name.isBlank())
                ? "Custom qbreader packet" : name.trim();
        Integer diff = difficulties == null || difficulties.isEmpty() ? tossups.get(0).difficulty() : difficulties.get(0);
        return assemble(uniqueName(packetName), diff, tossups, bonuses);
    }

    /* -------------------------------------------------------------------- */

    private Packet assemble(String packetName, Integer packetDifficulty,
                            List<QbTossup> tossups, List<QbBonus> bonuses) {
        Difficulty difficulty = findOrCreateDifficulty(difficultyLabel(packetDifficulty));
        Packet packet = authoring.createPacket(new CreatePacketInput(packetName, difficulty.getId()));

        int order = 0;
        for (QbTossup t : tossups) {
            Subcategory sub = findOrCreateSubcategory(t.category(), t.subcategory());
            packet = authoring.addTossupToPacket(packet.getId(),
                    new TossupInput(clean(t.question()), clean(t.answer()), sub.getId()), order++);
        }

        order = 0;
        if (bonuses != null) {
            for (QbBonus b : bonuses) {
                if (b.parts() == null || b.parts().isEmpty()) {
                    continue;
                }
                Subcategory sub = findOrCreateSubcategory(b.category(), b.subcategory());
                List<BonusPartInput> parts = new ArrayList<>();
                for (int i = 0; i < b.parts().size(); i++) {
                    String answer = (b.answers() != null && i < b.answers().size()) ? b.answers().get(i) : "";
                    parts.add(new BonusPartInput(clean(b.parts().get(i)), clean(answer)));
                }
                packet = authoring.addBonusToPacket(packet.getId(),
                        new BonusInput(clean(b.leadin()), sub.getId(), parts), order++);
            }
        }
        log.info("Imported qbreader packet '{}' (id={}, {} tossups, {} bonuses)",
                packetName, packet.getId(), tossups.size(), bonuses == null ? 0 : bonuses.size());
        return packet;
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

    private Category findOrCreateCategory(String name) {
        String catName = blankTo(name, "Miscellaneous");
        return categoryRepository.findByName(catName)
                .orElseGet(() -> authoring.createCategory(catName));
    }

    private Subcategory findOrCreateSubcategory(String categoryName, String subcategoryName) {
        String catName = blankTo(categoryName, "Miscellaneous");
        String subName = blankTo(subcategoryName, catName);
        Category category = findOrCreateCategory(catName);
        return subcategoryRepository.findByNameAndCategoryName(subName, catName)
                .orElseGet(() -> authoring.createSubcategory(subName, category.getId()));
    }

    private Difficulty findOrCreateDifficulty(String label) {
        return difficultyRepository.findByName(label)
                .orElseGet(() -> authoring.createDifficulty(label));
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

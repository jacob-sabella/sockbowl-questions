package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.api.input.BonusInput;
import com.soulsoftworks.sockbowlquestions.api.input.BonusPartInput;
import com.soulsoftworks.sockbowlquestions.api.input.BonusUpdateInput;
import com.soulsoftworks.sockbowlquestions.api.input.CreatePacketInput;
import com.soulsoftworks.sockbowlquestions.api.input.GenerateTossupInput;
import com.soulsoftworks.sockbowlquestions.api.input.TossupInput;
import com.soulsoftworks.sockbowlquestions.config.AiSecurityProperties;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.repository.BonusPartRepository;
import com.soulsoftworks.sockbowlquestions.repository.BonusRepository;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.TossupRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orchestrates manual packet/question authoring against the existing Neo4j
 * domain model. Owns relationship {@code order} maintenance and input
 * validation so the GraphQL resolvers stay thin.
 *
 * <p>Order convention: every ordered relationship collection is kept dense and
 * zero-based after each mutation. Adds may specify a target index; out-of-range
 * indices are clamped (null/large = append). Removes and reorders re-normalise
 * the remaining items to {@code 0..n-1}.
 */
@Service
@Slf4j
public class PacketAuthoringService {

    private final PacketRepository packetRepository;
    private final TossupRepository tossupRepository;
    private final BonusRepository bonusRepository;
    private final BonusPartRepository bonusPartRepository;
    private final DifficultyRepository difficultyRepository;
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final QuestionGenerationService questionGenerationService;
    private final AiSecurityProperties aiSecurityProperties;

    public PacketAuthoringService(PacketRepository packetRepository,
                                  TossupRepository tossupRepository,
                                  BonusRepository bonusRepository,
                                  BonusPartRepository bonusPartRepository,
                                  DifficultyRepository difficultyRepository,
                                  CategoryRepository categoryRepository,
                                  SubcategoryRepository subcategoryRepository,
                                  QuestionGenerationService questionGenerationService,
                                  AiSecurityProperties aiSecurityProperties) {
        this.packetRepository = packetRepository;
        this.tossupRepository = tossupRepository;
        this.bonusRepository = bonusRepository;
        this.bonusPartRepository = bonusPartRepository;
        this.difficultyRepository = difficultyRepository;
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.questionGenerationService = questionGenerationService;
        this.aiSecurityProperties = aiSecurityProperties;
    }

    /* ------------------------------- Packet -------------------------------- */

    @Transactional
    public Packet createPacket(CreatePacketInput input, String ownerId, String ownerDisplayName) {
        String name = requireText(input.name(), "Packet name");
        Packet.PacketBuilder builder = Packet.builder().name(name)
                .ownerId(ownerId)
                .ownerDisplayName(ownerDisplayName);
        if (input.difficultyId() != null && !input.difficultyId().isBlank()) {
            builder.difficulty(requireDifficulty(input.difficultyId()));
        }
        return packetRepository.save(builder.build());
    }

    @Transactional
    public Packet renamePacket(String id, String name) {
        Packet packet = requirePacket(id);
        packet.setName(requireText(name, "Packet name"));
        return packetRepository.save(packet);
    }

    @Transactional
    public Packet setPacketDifficulty(String id, String difficultyId) {
        Packet packet = requirePacket(id);
        packet.setDifficulty(requireDifficulty(difficultyId));
        return packetRepository.save(packet);
    }

    @Transactional
    public boolean deletePacket(String id) {
        if (!packetRepository.existsById(id)) {
            throw ResourceNotFoundException.of("Packet", id);
        }
        // Cascade to the packet-owned question nodes so generated/authored packets
        // don't leave orphaned tossups/bonuses/parts behind.
        packetRepository.deletePacketCascade(id);
        return true;
    }

    /* ------------------------------- Tossups ------------------------------- */

    @Transactional
    public Packet addTossupToPacket(String packetId, TossupInput input, Integer order) {
        Packet packet = requirePacket(packetId);
        Tossup tossup = Tossup.builder()
                .question(requireText(input.question(), "Tossup question"))
                .answer(requireText(input.answer(), "Tossup answer"))
                .subcategory(optionalSubcategory(input.subcategoryId()))
                .build();

        List<ContainsTossup> rels = sortedTossups(packet);
        int idx = resolveInsertIndex(order, rels.size());
        rels.add(idx, ContainsTossup.builder().order(idx).tossup(tossup).build());
        renumberTossups(rels);
        packet.setTossups(rels);
        return packetRepository.save(packet);
    }

    @Transactional
    public Tossup updateTossup(String id, TossupInput input) {
        Tossup tossup = requireTossup(id);
        tossup.setQuestion(requireText(input.question(), "Tossup question"));
        tossup.setAnswer(requireText(input.answer(), "Tossup answer"));
        if (input.subcategoryId() != null) {
            tossup.setSubcategory(optionalSubcategory(input.subcategoryId()));
        }
        return tossupRepository.save(tossup);
    }

    @Transactional
    public Packet removeTossupFromPacket(String packetId, String tossupId) {
        Packet packet = requirePacket(packetId);
        List<ContainsTossup> rels = sortedTossups(packet);
        boolean removed = rels.removeIf(rel -> rel.getTossup() != null
                && tossupId.equals(rel.getTossup().getId()));
        if (!removed) {
            throw new ResourceNotFoundException(
                    "Tossup " + tossupId + " is not part of packet " + packetId);
        }
        renumberTossups(rels);
        packet.setTossups(rels);
        Packet saved = packetRepository.save(packet);
        tossupRepository.deleteById(tossupId);
        return saved;
    }

    @Transactional
    public Packet reorderTossup(String packetId, String tossupId, int newOrder) {
        Packet packet = requirePacket(packetId);
        List<ContainsTossup> rels = sortedTossups(packet);
        ContainsTossup moving = rels.stream()
                .filter(rel -> rel.getTossup() != null && tossupId.equals(rel.getTossup().getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tossup " + tossupId + " is not part of packet " + packetId));
        rels.remove(moving);
        rels.add(clampMoveIndex(newOrder, rels.size()), moving);
        renumberTossups(rels);
        packet.setTossups(rels);
        return packetRepository.save(packet);
    }

    /* -------------------------------- Bonuses ------------------------------ */

    @Transactional
    public Packet addBonusToPacket(String packetId, BonusInput input, Integer order) {
        Packet packet = requirePacket(packetId);
        Bonus bonus = new Bonus();
        bonus.setPreamble(input.preamble());
        bonus.setSubcategory(optionalSubcategory(input.subcategoryId()));
        bonus.setBonusParts(buildBonusParts(input.parts()));

        List<Bonus> ordered = orderedBonuses(packet);
        int idx = resolveInsertIndex(order, ordered.size());
        ordered.add(idx, bonus);
        packet.setBonuses(rebuildContainsBonus(ordered));
        return packetRepository.save(packet);
    }

    @Transactional
    public Bonus updateBonus(String id, BonusUpdateInput input) {
        Bonus bonus = requireBonus(id);
        bonus.setPreamble(input.preamble());
        if (input.subcategoryId() != null) {
            bonus.setSubcategory(optionalSubcategory(input.subcategoryId()));
        }
        return bonusRepository.save(bonus);
    }

    @Transactional
    public Packet removeBonusFromPacket(String packetId, String bonusId) {
        Packet packet = requirePacket(packetId);
        List<Bonus> ordered = orderedBonuses(packet);
        boolean removed = ordered.removeIf(b -> bonusId.equals(b.getId()));
        if (!removed) {
            throw new ResourceNotFoundException(
                    "Bonus " + bonusId + " is not part of packet " + packetId);
        }
        packet.setBonuses(rebuildContainsBonus(ordered));
        Packet saved = packetRepository.save(packet);
        bonusRepository.deleteById(bonusId);
        return saved;
    }

    @Transactional
    public Packet reorderBonus(String packetId, String bonusId, int newOrder) {
        Packet packet = requirePacket(packetId);
        List<Bonus> ordered = orderedBonuses(packet);
        Bonus moving = ordered.stream()
                .filter(b -> bonusId.equals(b.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Bonus " + bonusId + " is not part of packet " + packetId));
        ordered.remove(moving);
        ordered.add(clampMoveIndex(newOrder, ordered.size()), moving);
        packet.setBonuses(rebuildContainsBonus(ordered));
        return packetRepository.save(packet);
    }

    /* ------------------------------ Bonus parts ---------------------------- */

    @Transactional
    public Bonus addBonusPart(String bonusId, BonusPartInput input, Integer order) {
        Bonus bonus = requireBonus(bonusId);
        BonusPart part = buildBonusPart(input);

        List<BonusPart> ordered = orderedBonusParts(bonus);
        int idx = resolveInsertIndex(order, ordered.size());
        ordered.add(idx, part);
        bonus.setBonusParts(rebuildHasBonusPart(ordered));
        return bonusRepository.save(bonus);
    }

    @Transactional
    public Bonus updateBonusPart(String bonusId, String bonusPartId, BonusPartInput input) {
        Bonus bonus = requireBonus(bonusId);
        BonusPart part = orderedBonusParts(bonus).stream()
                .filter(p -> bonusPartId.equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Bonus part " + bonusPartId + " is not part of bonus " + bonusId));
        part.setQuestion(requireText(input.question(), "Bonus part question"));
        part.setAnswer(requireText(input.answer(), "Bonus part answer"));
        return bonusRepository.save(bonus);
    }

    @Transactional
    public Bonus removeBonusPart(String bonusId, String bonusPartId) {
        Bonus bonus = requireBonus(bonusId);
        List<BonusPart> ordered = orderedBonusParts(bonus);
        boolean removed = ordered.removeIf(p -> bonusPartId.equals(p.getId()));
        if (!removed) {
            throw new ResourceNotFoundException(
                    "Bonus part " + bonusPartId + " is not part of bonus " + bonusId);
        }
        bonus.setBonusParts(rebuildHasBonusPart(ordered));
        Bonus saved = bonusRepository.save(bonus);
        bonusPartRepository.deleteById(bonusPartId);
        return saved;
    }

    @Transactional
    public Bonus reorderBonusPart(String bonusId, String bonusPartId, int newOrder) {
        Bonus bonus = requireBonus(bonusId);
        List<BonusPart> ordered = orderedBonusParts(bonus);
        BonusPart moving = ordered.stream()
                .filter(p -> bonusPartId.equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Bonus part " + bonusPartId + " is not part of bonus " + bonusId));
        ordered.remove(moving);
        ordered.add(clampMoveIndex(newOrder, ordered.size()), moving);
        bonus.setBonusParts(rebuildHasBonusPart(ordered));
        return bonusRepository.save(bonus);
    }

    /* ------------------------------- Taxonomy ------------------------------ */

    @Transactional
    public Difficulty createDifficulty(String name) {
        Difficulty difficulty = new Difficulty();
        difficulty.setName(requireText(name, "Difficulty name"));
        return difficultyRepository.save(difficulty);
    }

    @Transactional
    public Category createCategory(String name) {
        Category category = Category.builder().name(requireText(name, "Category name")).build();
        return categoryRepository.save(category);
    }

    @Transactional
    public Subcategory createSubcategory(String name, String categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> ResourceNotFoundException.of("Category", categoryId));
        Subcategory subcategory = Subcategory.builder()
                .name(requireText(name, "Subcategory name"))
                .category(category)
                .build();
        return subcategoryRepository.save(subcategory);
    }

    @Transactional
    public Tossup setTossupSubcategory(String tossupId, String subcategoryId) {
        Tossup tossup = requireTossup(tossupId);
        tossup.setSubcategory(requireSubcategory(subcategoryId));
        return tossupRepository.save(tossup);
    }

    @Transactional
    public Bonus setBonusSubcategory(String bonusId, String subcategoryId) {
        Bonus bonus = requireBonus(bonusId);
        bonus.setSubcategory(requireSubcategory(subcategoryId));
        return bonusRepository.save(bonus);
    }

    /* ------------------------------- AI assist ----------------------------- */

    /**
     * AI-assisted authoring: generates a single tossup via the existing
     * {@link QuestionGenerationService} and appends it to the packet. Reuses the
     * same security rules as the REST generation endpoint.
     */
    @Transactional
    public Packet generateAndAddTossup(String packetId, GenerateTossupInput input, Integer order) {
        Packet packet = requirePacket(packetId);
        String topic = requireText(input.topic(), "Topic");

        AiRequestContext context = AiRequestContext.builder()
                .apiKey(input.apiKey())
                .model(input.model())
                .build();
        validateAiRequest(context);

        List<Tossup> existing = sortedTossups(packet).stream()
                .map(ContainsTossup::getTossup)
                .filter(java.util.Objects::nonNull)
                .toList();

        Tossup generated = questionGenerationService.generateTossup(
                topic, input.additionalContext(), existing, context);
        if (generated == null) {
            throw new InvalidApiRequestException("AI generation returned no tossup");
        }
        // Reset any transient id so it is persisted as a new node, and apply an
        // explicit subcategory override when supplied.
        generated.setId(null);
        if (input.subcategoryId() != null && !input.subcategoryId().isBlank()) {
            generated.setSubcategory(requireSubcategory(input.subcategoryId()));
        }

        List<ContainsTossup> rels = sortedTossups(packet);
        int idx = resolveInsertIndex(order, rels.size());
        rels.add(idx, ContainsTossup.builder().order(idx).tossup(generated).build());
        renumberTossups(rels);
        packet.setTossups(rels);
        return packetRepository.save(packet);
    }

    /* ------------------------------- Helpers ------------------------------- */

    private void validateAiRequest(AiRequestContext context) {
        if (aiSecurityProperties.isRequireUserApiKey() && !context.hasCustomConfig()) {
            throw new InvalidApiRequestException(
                    "API key is required. Please provide apiKey in the input.");
        }
        if (context.hasCustomConfig() && !context.isComplete()) {
            throw new InvalidApiRequestException(
                    "When providing apiKey, model is also required.");
        }
    }

    private List<ContainsTossup> sortedTossups(Packet packet) {
        List<ContainsTossup> rels = new ArrayList<>(
                packet.getTossups() == null ? List.of() : packet.getTossups());
        rels.sort(Comparator.comparingInt(r -> orderOrMax(r.getOrder())));
        return rels;
    }

    private List<Bonus> orderedBonuses(Packet packet) {
        List<ContainsBonus> rels = new ArrayList<>(
                packet.getBonuses() == null ? List.of() : packet.getBonuses());
        rels.sort(Comparator.comparingInt(r -> orderOrMax(r.getOrder())));
        List<Bonus> ordered = new ArrayList<>();
        for (ContainsBonus rel : rels) {
            ordered.add(rel.getBonus());
        }
        return ordered;
    }

    private List<BonusPart> orderedBonusParts(Bonus bonus) {
        List<HasBonusPart> rels = new ArrayList<>(
                bonus.getBonusParts() == null ? List.of() : bonus.getBonusParts());
        rels.sort(Comparator.comparingInt(r -> orderOrMax(r.getOrder())));
        List<BonusPart> ordered = new ArrayList<>();
        for (HasBonusPart rel : rels) {
            ordered.add(rel.getBonusPart());
        }
        return ordered;
    }

    private List<HasBonusPart> buildBonusParts(List<BonusPartInput> parts) {
        List<HasBonusPart> result = new ArrayList<>();
        if (parts == null) {
            return result;
        }
        for (int i = 0; i < parts.size(); i++) {
            result.add(new HasBonusPart(i, buildBonusPart(parts.get(i))));
        }
        return result;
    }

    private BonusPart buildBonusPart(BonusPartInput input) {
        BonusPart part = new BonusPart();
        part.setQuestion(requireText(input.question(), "Bonus part question"));
        part.setAnswer(requireText(input.answer(), "Bonus part answer"));
        return part;
    }

    private List<ContainsBonus> rebuildContainsBonus(List<Bonus> ordered) {
        List<ContainsBonus> rels = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            rels.add(new ContainsBonus(i, ordered.get(i)));
        }
        return rels;
    }

    private List<HasBonusPart> rebuildHasBonusPart(List<BonusPart> ordered) {
        List<HasBonusPart> rels = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            rels.add(new HasBonusPart(i, ordered.get(i)));
        }
        return rels;
    }

    private void renumberTossups(List<ContainsTossup> rels) {
        for (int i = 0; i < rels.size(); i++) {
            rels.get(i).setOrder(i);
        }
    }

    private Subcategory optionalSubcategory(String subcategoryId) {
        if (subcategoryId == null || subcategoryId.isBlank()) {
            return null;
        }
        return requireSubcategory(subcategoryId);
    }

    private Packet requirePacket(String id) {
        return packetRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Packet", id));
    }

    private Tossup requireTossup(String id) {
        return tossupRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Tossup", id));
    }

    private Bonus requireBonus(String id) {
        return bonusRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Bonus", id));
    }

    private Difficulty requireDifficulty(String id) {
        return difficultyRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Difficulty", id));
    }

    private Subcategory requireSubcategory(String id) {
        return subcategoryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Subcategory", id));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidApiRequestException(field + " must not be blank");
        }
        return value.trim();
    }

    private static int orderOrMax(Integer order) {
        return order == null ? Integer.MAX_VALUE : order;
    }

    /** Insert index for adds: null/oversized appends, negatives clamp to 0. */
    private static int resolveInsertIndex(Integer requested, int size) {
        if (requested == null || requested > size) {
            return size;
        }
        return Math.max(requested, 0);
    }

    /** Target index for reorders: clamped to the valid {@code 0..size-1} range. */
    private static int clampMoveIndex(int newOrder, int sizeAfterRemoval) {
        if (newOrder < 0) {
            return 0;
        }
        return Math.min(newOrder, sizeAfterRemoval);
    }
}

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
import com.soulsoftworks.sockbowlquestions.exception.PacketVersionConflictException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.quota.ContentQuotaGuard;
import com.soulsoftworks.sockbowlquestions.repository.BonusPartRepository;
import com.soulsoftworks.sockbowlquestions.repository.BonusRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.TossupRepository;
import com.soulsoftworks.sockbowlquestions.security.SecurityAuditorAware;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
 *
 * <p>Optimistic locking (PB-18, plan 3.1.4): every mutation that targets an existing
 * packet or its content takes an {@code expectedVersion}, the packet version the caller
 * last saw, and calls {@link #bumpVersion} <em>first</em>, before loading anything it will
 * save. The bump takes the packet's write lock for the rest of the transaction (so
 * concurrent mutations of one packet are serialized) and rejects a stale
 * {@code expectedVersion} with {@link PacketVersionConflictException}. Null skips the
 * check (the game and older clients) but still bumps. Node-level mutations resolve the
 * packet through the tossup or bonus; a node outside any packet has no version to bump.
 *
 * <p>Validation (PB-11, plan 3.1.5): field lengths and structural caps come from
 * {@link PacketValidator}.
 *
 * <p>Taxonomy creation lives in {@link TaxonomyService} (moved in M3 Q1).
 */
@Service
@Slf4j
public class PacketAuthoringService {

    private final PacketRepository packetRepository;
    private final TossupRepository tossupRepository;
    private final BonusRepository bonusRepository;
    private final BonusPartRepository bonusPartRepository;
    private final DifficultyRepository difficultyRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final QuestionGenerationService questionGenerationService;
    private final AiSecurityProperties aiSecurityProperties;
    private final ContentQuotaGuard contentQuotaGuard;
    private final PacketValidator validator;
    private final SecurityAuditorAware securityAuditorAware;

    public PacketAuthoringService(PacketRepository packetRepository,
                                  TossupRepository tossupRepository,
                                  BonusRepository bonusRepository,
                                  BonusPartRepository bonusPartRepository,
                                  DifficultyRepository difficultyRepository,
                                  SubcategoryRepository subcategoryRepository,
                                  QuestionGenerationService questionGenerationService,
                                  AiSecurityProperties aiSecurityProperties,
                                  ContentQuotaGuard contentQuotaGuard,
                                  PacketValidator validator,
                                  SecurityAuditorAware securityAuditorAware) {
        this.packetRepository = packetRepository;
        this.tossupRepository = tossupRepository;
        this.bonusRepository = bonusRepository;
        this.bonusPartRepository = bonusPartRepository;
        this.difficultyRepository = difficultyRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.questionGenerationService = questionGenerationService;
        this.aiSecurityProperties = aiSecurityProperties;
        this.contentQuotaGuard = contentQuotaGuard;
        this.validator = validator;
        this.securityAuditorAware = securityAuditorAware;
    }

    /* ------------------------------- Packet -------------------------------- */

    // Q-V1-01: deliberately NOT @Transactional. A method-level @Transactional here
    // would wrap the whole withPacketsOwnedSlot call (lock acquire, count, save,
    // lock release) in one Spring transaction that only commits when this method
    // RETURNS — after the lock is already released. A second caller could then
    // acquire the freed lock and count the still-uncommitted save away, overshooting
    // the quota despite the lock. packetRepository.save() below is transactional on
    // its own (Spring Data's generated repository methods are), and commits before
    // withPacketsOwnedSlot releases the lock, which is what actually closes the race.
    public Packet createPacket(CreatePacketInput input, String ownerId, String ownerDisplayName) {
        String name = validator.packetName(input.name());
        // M4-UQ-01 / Q-V1-01: packets-owned quota (D10), serialized per owner so
        // concurrent creates can't overshoot it; recovers when the owner deletes a packet.
        return contentQuotaGuard.withPacketsOwnedSlot(ownerId, () -> {
            Packet.PacketBuilder builder = Packet.builder().name(name)
                    // M3 Q2: optimistic-lock version starts at 0.
                    .version(0L)
                    .ownerId(ownerId)
                    .ownerDisplayName(ownerDisplayName)
                    // D2: new packets start as drafts; the owner publishes via setPacketVisibility.
                    .visibility(PacketVisibility.defaultForNewPackets())
                    // D13, M4-PV-01: hand-authored through this API.
                    .source(ContentSource.AUTHORED);
            if (input.difficultyId() != null && !input.difficultyId().isBlank()) {
                builder.difficulty(requireDifficulty(input.difficultyId()));
            }
            return packetRepository.save(builder.build());
        });
    }

    @Transactional
    public Packet renamePacket(String id, String name, Integer expectedVersion) {
        String validName = validator.packetName(name);
        bumpVersion(id, expectedVersion);
        Packet packet = requirePacket(id);
        packet.setName(validName);
        return packetRepository.save(packet);
    }

    /**
     * Change who may read a packet (D2). Authorization (packet:update plus ownership or
     * manage-any) is enforced on the GraphQL mutation.
     */
    @Transactional
    public Packet setPacketVisibility(String id, PacketVisibility visibility, Integer expectedVersion) {
        if (visibility == null) {
            throw new InvalidApiRequestException("Packet visibility is required");
        }
        if (visibility.isGameOnly()) {
            // D15: EPHEMERAL packets only come from import-random for guests and players.
            throw new InvalidApiRequestException("Packet visibility " + visibility + " can't be set directly");
        }
        bumpVersion(id, expectedVersion);
        Packet packet = requirePacket(id);
        packet.setVisibility(visibility);
        return packetRepository.save(packet);
    }

    @Transactional
    public Packet setPacketDifficulty(String id, String difficultyId, Integer expectedVersion) {
        bumpVersion(id, expectedVersion);
        Packet packet = requirePacket(id);
        packet.setDifficulty(requireDifficulty(difficultyId));
        return packetRepository.save(packet);
    }

    @Transactional
    public boolean deletePacket(String id, Integer expectedVersion) {
        if (!packetRepository.existsById(id)) {
            throw ResourceNotFoundException.of("Packet", id);
        }
        bumpVersion(id, expectedVersion);
        // Cascade to the packet-owned question nodes so generated/authored packets
        // don't leave orphaned tossups/bonuses/parts behind.
        packetRepository.deletePacketCascade(id);
        return true;
    }

    /* ------------------------------- Tossups ------------------------------- */

    @Transactional
    public Packet addTossupToPacket(String packetId, TossupInput input, Integer order,
                                    Integer expectedVersion) {
        // A rejected input rolls the whole transaction back, bump included.
        bumpVersion(packetId, expectedVersion);
        Packet packet = requirePacket(packetId);
        String question = validator.question(input.question(), "Tossup question");
        String answer = validator.answer(input.answer(), "Tossup answer");
        List<ContainsTossup> rels = sortedTossups(packet);
        validator.checkCanAddTossup(rels.size());
        Tossup tossup = Tossup.builder()
                .question(question)
                .answer(answer)
                .subcategory(optionalSubcategory(input.subcategoryId()))
                // D13, M4-PV-01: hand-authored through this API.
                .source(ContentSource.AUTHORED)
                .build();

        int idx = resolveInsertIndex(order, rels.size());
        rels.add(idx, ContainsTossup.builder().order(idx).tossup(tossup).build());
        renumberTossups(rels);
        packet.setTossups(rels);
        return packetRepository.save(packet);
    }

    @Transactional
    public Tossup updateTossup(String id, TossupInput input, Integer expectedVersion) {
        String question = validator.question(input.question(), "Tossup question");
        String answer = validator.answer(input.answer(), "Tossup answer");
        bumpVersionForTossup(id, expectedVersion);
        Tossup tossup = requireTossup(id);
        tossup.setQuestion(question);
        tossup.setAnswer(answer);
        if (input.subcategoryId() != null) {
            tossup.setSubcategory(optionalSubcategory(input.subcategoryId()));
        }
        // D13, M4-PV-01: a manual edit through this API makes the content AUTHORED
        // from here on, whatever it started as (imported or AI-generated).
        tossup.setSource(ContentSource.AUTHORED);
        return tossupRepository.save(tossup);
    }

    @Transactional
    public Packet removeTossupFromPacket(String packetId, String tossupId, Integer expectedVersion) {
        bumpVersion(packetId, expectedVersion);
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
    public Packet reorderTossup(String packetId, String tossupId, int newOrder,
                                Integer expectedVersion) {
        bumpVersion(packetId, expectedVersion);
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
    public Packet addBonusToPacket(String packetId, BonusInput input, Integer order,
                                   Integer expectedVersion) {
        validator.checkNewBonusParts(input.parts() == null ? 0 : input.parts().size());
        String preamble = validator.preamble(input.preamble());
        List<HasBonusPart> parts = buildBonusParts(input.parts());
        bumpVersion(packetId, expectedVersion);
        Packet packet = requirePacket(packetId);
        List<Bonus> ordered = orderedBonuses(packet);
        validator.checkCanAddBonus(ordered.size());
        Bonus bonus = new Bonus();
        bonus.setPreamble(preamble);
        bonus.setSubcategory(optionalSubcategory(input.subcategoryId()));
        bonus.setBonusParts(parts);
        // D13, M4-PV-01: hand-authored through this API.
        bonus.setSource(ContentSource.AUTHORED);

        int idx = resolveInsertIndex(order, ordered.size());
        ordered.add(idx, bonus);
        packet.setBonuses(rebuildContainsBonus(ordered));
        return packetRepository.save(packet);
    }

    @Transactional
    public Bonus updateBonus(String id, BonusUpdateInput input, Integer expectedVersion) {
        String preamble = validator.preamble(input.preamble());
        bumpVersionForBonus(id, expectedVersion);
        Bonus bonus = requireBonus(id);
        bonus.setPreamble(preamble);
        if (input.subcategoryId() != null) {
            bonus.setSubcategory(optionalSubcategory(input.subcategoryId()));
        }
        // D13, M4-PV-01: a manual edit through this API makes the content AUTHORED
        // from here on, whatever it started as (imported or AI-generated).
        bonus.setSource(ContentSource.AUTHORED);
        return bonusRepository.save(bonus);
    }

    @Transactional
    public Packet removeBonusFromPacket(String packetId, String bonusId, Integer expectedVersion) {
        bumpVersion(packetId, expectedVersion);
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
    public Packet reorderBonus(String packetId, String bonusId, int newOrder,
                               Integer expectedVersion) {
        bumpVersion(packetId, expectedVersion);
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
    public Bonus addBonusPart(String bonusId, BonusPartInput input, Integer order,
                              Integer expectedVersion) {
        BonusPart part = buildBonusPart(input);
        bumpVersionForBonus(bonusId, expectedVersion);
        Bonus bonus = requireBonus(bonusId);
        List<BonusPart> ordered = orderedBonusParts(bonus);
        validator.checkCanAddPart(ordered.size());
        int idx = resolveInsertIndex(order, ordered.size());
        ordered.add(idx, part);
        bonus.setBonusParts(rebuildHasBonusPart(ordered));
        return bonusRepository.save(bonus);
    }

    @Transactional
    public Bonus updateBonusPart(String bonusId, String bonusPartId, BonusPartInput input,
                                 Integer expectedVersion) {
        String question = validator.question(input.question(), "Bonus part question");
        String answer = validator.answer(input.answer(), "Bonus part answer");
        bumpVersionForBonus(bonusId, expectedVersion);
        Bonus bonus = requireBonus(bonusId);
        BonusPart part = orderedBonusParts(bonus).stream()
                .filter(p -> bonusPartId.equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Bonus part " + bonusPartId + " is not part of bonus " + bonusId));
        part.setQuestion(question);
        part.setAnswer(answer);
        // D13, M4-PV-01: a manual edit through this API makes the content AUTHORED
        // from here on, whatever it started as (imported or AI-generated).
        part.setSource(ContentSource.AUTHORED);
        return bonusRepository.save(bonus);
    }

    @Transactional
    public Bonus removeBonusPart(String bonusId, String bonusPartId, Integer expectedVersion) {
        bumpVersionForBonus(bonusId, expectedVersion);
        Bonus bonus = requireBonus(bonusId);
        List<BonusPart> ordered = orderedBonusParts(bonus);
        if (ordered.stream().noneMatch(p -> bonusPartId.equals(p.getId()))) {
            throw new ResourceNotFoundException(
                    "Bonus part " + bonusPartId + " is not part of bonus " + bonusId);
        }
        validator.checkCanRemovePart(ordered.size());
        ordered.removeIf(p -> bonusPartId.equals(p.getId()));
        bonus.setBonusParts(rebuildHasBonusPart(ordered));
        Bonus saved = bonusRepository.save(bonus);
        bonusPartRepository.deleteById(bonusPartId);
        return saved;
    }

    @Transactional
    public Bonus reorderBonusPart(String bonusId, String bonusPartId, int newOrder,
                                  Integer expectedVersion) {
        bumpVersionForBonus(bonusId, expectedVersion);
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

    /* ----------------------------- Subcategory ----------------------------- */

    /**
     * Sets or clears (PB-09, plan 3.1.7) a tossup's subcategory. A null or blank
     * {@code subcategoryId} deletes the {@code SUBCATEGORY_IS} relationship.
     */
    @Transactional
    public Tossup setTossupSubcategory(String tossupId, String subcategoryId, Integer expectedVersion) {
        bumpVersionForTossup(tossupId, expectedVersion);
        Tossup tossup = requireTossup(tossupId);
        if (isBlank(subcategoryId)) {
            tossupRepository.clearSubcategory(tossupId);
            tossup.setSubcategory(null);
        } else {
            tossup.setSubcategory(requireSubcategory(subcategoryId));
        }
        return tossupRepository.save(tossup);
    }

    /** Sets or clears a bonus's subcategory; see {@link #setTossupSubcategory}. */
    @Transactional
    public Bonus setBonusSubcategory(String bonusId, String subcategoryId, Integer expectedVersion) {
        bumpVersionForBonus(bonusId, expectedVersion);
        Bonus bonus = requireBonus(bonusId);
        if (isBlank(subcategoryId)) {
            bonusRepository.clearSubcategory(bonusId);
            bonus.setSubcategory(null);
        } else {
            bonus.setSubcategory(requireSubcategory(subcategoryId));
        }
        return bonusRepository.save(bonus);
    }

    /* ------------------------------- AI assist ----------------------------- */

    /**
     * AI-assisted authoring: generates a single tossup via the existing
     * {@link QuestionGenerationService} and appends it to the packet. Reuses the
     * same security rules as the REST generation endpoint.
     */
    @Transactional
    public Packet generateAndAddTossup(String packetId, GenerateTossupInput input, Integer order,
                                       Integer expectedVersion) {
        return generateAndAddTossup(packetId, input, order, expectedVersion, null);
    }

    /**
     * As above; {@code savedKeyContext} (the caller's saved Claude key, resolved by
     * the controller) is used when the input carries no {@code apiKey}.
     */
    @Transactional
    public Packet generateAndAddTossup(String packetId, GenerateTossupInput input, Integer order,
                                       Integer expectedVersion, AiRequestContext savedKeyContext) {
        String topic = requireText(input.topic(), "Topic");
        bumpVersion(packetId, expectedVersion);
        Packet packet = requirePacket(packetId);
        validator.checkCanAddTossup(sortedTossups(packet).size());

        AiRequestContext context = AiRequestContext.builder()
                .apiKey(input.apiKey())
                .model(input.model())
                .build();
        if (!context.hasCustomConfig() && savedKeyContext != null) {
            context = savedKeyContext;
        }
        validateAiRequest(context);

        List<Tossup> existing = sortedTossups(packet).stream()
                .map(ContainsTossup::getTossup)
                .filter(java.util.Objects::nonNull)
                .toList();

        Tossup generated = questionGenerationService.generateTossup(
                topic, input.additionalContext(), packet.getDifficulty(), existing, context);
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
        part.setQuestion(validator.question(input.question(), "Bonus part question"));
        part.setAnswer(validator.answer(input.answer(), "Bonus part answer"));
        // D13, M4-PV-01: hand-authored through this API.
        part.setSource(ContentSource.AUTHORED);
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

    /**
     * Locks the packet, checks {@code expectedVersion} and bumps the version (plan 3.1.4).
     * Must run before the packet or its content is loaded for saving, so the later save
     * writes the bumped version.
     *
     * @throws PacketVersionConflictException when {@code expectedVersion} is stale
     * @throws ResourceNotFoundException      when the packet doesn't exist
     */
    private void bumpVersion(String packetId, Integer expectedVersion) {
        Long expected = expectedVersion == null ? null : expectedVersion.longValue();
        // D13, M4-PV-01, INT1: stamps the packet's lastModifiedBy/lastModifiedAt on every
        // successful bump, since a node-level mutation (tossup/bonus/bonus-part) never
        // re-saves the packet itself.
        Long bumped = packetRepository.bumpVersion(packetId, expected,
                securityAuditorAware.currentAuditorValue(), Instant.now().toString());
        if (bumped != null) {
            return;
        }
        Long current = packetRepository.currentVersion(packetId)
                .orElseThrow(() -> ResourceNotFoundException.of("Packet", packetId));
        throw new PacketVersionConflictException(packetId, expected, current);
    }

    /** {@link #bumpVersion} on the packet containing the tossup; no-op for a tossup outside any packet. */
    private void bumpVersionForTossup(String tossupId, Integer expectedVersion) {
        packetRepository.findPacketIdByTossupId(tossupId)
                .ifPresent(packetId -> bumpVersion(packetId, expectedVersion));
    }

    /** {@link #bumpVersion} on the packet containing the bonus; no-op for a bonus outside any packet. */
    private void bumpVersionForBonus(String bonusId, Integer expectedVersion) {
        packetRepository.findPacketIdByBonusId(bonusId)
                .ifPresent(packetId -> bumpVersion(packetId, expectedVersion));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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

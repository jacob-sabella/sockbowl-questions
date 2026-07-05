package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.api.input.BonusInput;
import com.soulsoftworks.sockbowlquestions.api.input.BonusPartInput;
import com.soulsoftworks.sockbowlquestions.api.input.BonusUpdateInput;
import com.soulsoftworks.sockbowlquestions.api.input.CreatePacketInput;
import com.soulsoftworks.sockbowlquestions.api.input.GenerateTossupInput;
import com.soulsoftworks.sockbowlquestions.api.input.TossupInput;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.service.PacketAuthoringService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * Thin GraphQL mutation layer for packet/question authoring. All orchestration,
 * ordering, and validation live in {@link PacketAuthoringService}.
 */
@Controller
public class PacketAuthoringController {

    private final PacketAuthoringService authoringService;

    public PacketAuthoringController(PacketAuthoringService authoringService) {
        this.authoringService = authoringService;
    }

    /* ------------------------------- Packet -------------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create')")
    public Packet createPacket(@Argument CreatePacketInput input) {
        return authoringService.createPacket(input);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update')")
    public Packet renamePacket(@Argument String id, @Argument String name) {
        return authoringService.renamePacket(id, name);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update')")
    public Packet setPacketDifficulty(@Argument String id, @Argument String difficultyId) {
        return authoringService.setPacketDifficulty(id, difficultyId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete')")
    public boolean deletePacket(@Argument String id) {
        return authoringService.deletePacket(id);
    }

    /* ------------------------------- Tossups ------------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create')")
    public Packet addTossupToPacket(@Argument String packetId,
                                    @Argument TossupInput input,
                                    @Argument Integer order) {
        return authoringService.addTossupToPacket(packetId, input, order);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update')")
    public Tossup updateTossup(@Argument String id, @Argument TossupInput input) {
        return authoringService.updateTossup(id, input);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete')")
    public Packet removeTossupFromPacket(@Argument String packetId, @Argument String tossupId) {
        return authoringService.removeTossupFromPacket(packetId, tossupId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update')")
    public Packet reorderTossup(@Argument String packetId,
                                @Argument String tossupId,
                                @Argument int newOrder) {
        return authoringService.reorderTossup(packetId, tossupId, newOrder);
    }

    /* -------------------------------- Bonuses ------------------------------ */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create')")
    public Packet addBonusToPacket(@Argument String packetId,
                                   @Argument BonusInput input,
                                   @Argument Integer order) {
        return authoringService.addBonusToPacket(packetId, input, order);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update')")
    public Bonus updateBonus(@Argument String id, @Argument BonusUpdateInput input) {
        return authoringService.updateBonus(id, input);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete')")
    public Packet removeBonusFromPacket(@Argument String packetId, @Argument String bonusId) {
        return authoringService.removeBonusFromPacket(packetId, bonusId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update')")
    public Packet reorderBonus(@Argument String packetId,
                               @Argument String bonusId,
                               @Argument int newOrder) {
        return authoringService.reorderBonus(packetId, bonusId, newOrder);
    }

    /* ------------------------------ Bonus parts ---------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create')")
    public Bonus addBonusPart(@Argument String bonusId,
                              @Argument BonusPartInput input,
                              @Argument Integer order) {
        return authoringService.addBonusPart(bonusId, input, order);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update')")
    public Bonus updateBonusPart(@Argument String bonusId,
                                 @Argument String bonusPartId,
                                 @Argument BonusPartInput input) {
        return authoringService.updateBonusPart(bonusId, bonusPartId, input);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete')")
    public Bonus removeBonusPart(@Argument String bonusId, @Argument String bonusPartId) {
        return authoringService.removeBonusPart(bonusId, bonusPartId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update')")
    public Bonus reorderBonusPart(@Argument String bonusId,
                                  @Argument String bonusPartId,
                                  @Argument int newOrder) {
        return authoringService.reorderBonusPart(bonusId, bonusPartId, newOrder);
    }

    /* ------------------------------- Taxonomy ------------------------------ */

    @MutationMapping
    @PreAuthorize("hasAuthority('taxonomy:manage')")
    public Difficulty createDifficulty(@Argument String name) {
        return authoringService.createDifficulty(name);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('taxonomy:manage')")
    public Category createCategory(@Argument String name) {
        return authoringService.createCategory(name);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('taxonomy:manage')")
    public Subcategory createSubcategory(@Argument String name, @Argument String categoryId) {
        return authoringService.createSubcategory(name, categoryId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update')")
    public Tossup setTossupSubcategory(@Argument String tossupId, @Argument String subcategoryId) {
        return authoringService.setTossupSubcategory(tossupId, subcategoryId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update')")
    public Bonus setBonusSubcategory(@Argument String bonusId, @Argument String subcategoryId) {
        return authoringService.setBonusSubcategory(bonusId, subcategoryId);
    }

    /* ------------------------------- AI assist ----------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('question:generate')")
    public Packet generateAndAddTossup(@Argument String packetId,
                                       @Argument GenerateTossupInput input,
                                       @Argument Integer order) {
        return authoringService.generateAndAddTossup(packetId, input, order);
    }
}

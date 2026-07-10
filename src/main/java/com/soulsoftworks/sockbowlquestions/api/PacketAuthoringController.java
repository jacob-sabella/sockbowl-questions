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
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlquestions.service.PacketAuthoringService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    public Packet createPacket(@Argument CreatePacketInput input, @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.fromJwt(jwt);
        return authoringService.createPacket(input, user.keycloakId(), user.username());
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManage(#id)")
    public Packet renamePacket(@Argument String id, @Argument String name) {
        return authoringService.renamePacket(id, name);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManage(#id)")
    public Packet setPacketDifficulty(@Argument String id, @Argument String difficultyId) {
        return authoringService.setPacketDifficulty(id, difficultyId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete') and @packetAuthorizationService.canManage(#id)")
    public boolean deletePacket(@Argument String id) {
        return authoringService.deletePacket(id);
    }

    /* ------------------------------- Tossups ------------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create') and @packetAuthorizationService.canManage(#packetId)")
    public Packet addTossupToPacket(@Argument String packetId,
                                    @Argument TossupInput input,
                                    @Argument Integer order) {
        return authoringService.addTossupToPacket(packetId, input, order);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageTossup(#id)")
    public Tossup updateTossup(@Argument String id, @Argument TossupInput input) {
        return authoringService.updateTossup(id, input);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete') and @packetAuthorizationService.canManage(#packetId)")
    public Packet removeTossupFromPacket(@Argument String packetId, @Argument String tossupId) {
        return authoringService.removeTossupFromPacket(packetId, tossupId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManage(#packetId)")
    public Packet reorderTossup(@Argument String packetId,
                                @Argument String tossupId,
                                @Argument int newOrder) {
        return authoringService.reorderTossup(packetId, tossupId, newOrder);
    }

    /* -------------------------------- Bonuses ------------------------------ */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create') and @packetAuthorizationService.canManage(#packetId)")
    public Packet addBonusToPacket(@Argument String packetId,
                                   @Argument BonusInput input,
                                   @Argument Integer order) {
        return authoringService.addBonusToPacket(packetId, input, order);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageBonus(#id)")
    public Bonus updateBonus(@Argument String id, @Argument BonusUpdateInput input) {
        return authoringService.updateBonus(id, input);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete') and @packetAuthorizationService.canManage(#packetId)")
    public Packet removeBonusFromPacket(@Argument String packetId, @Argument String bonusId) {
        return authoringService.removeBonusFromPacket(packetId, bonusId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManage(#packetId)")
    public Packet reorderBonus(@Argument String packetId,
                               @Argument String bonusId,
                               @Argument int newOrder) {
        return authoringService.reorderBonus(packetId, bonusId, newOrder);
    }

    /* ------------------------------ Bonus parts ---------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create') and @packetAuthorizationService.canManageBonus(#bonusId)")
    public Bonus addBonusPart(@Argument String bonusId,
                              @Argument BonusPartInput input,
                              @Argument Integer order) {
        return authoringService.addBonusPart(bonusId, input, order);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageBonus(#bonusId)")
    public Bonus updateBonusPart(@Argument String bonusId,
                                 @Argument String bonusPartId,
                                 @Argument BonusPartInput input) {
        return authoringService.updateBonusPart(bonusId, bonusPartId, input);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete') and @packetAuthorizationService.canManageBonus(#bonusId)")
    public Bonus removeBonusPart(@Argument String bonusId, @Argument String bonusPartId) {
        return authoringService.removeBonusPart(bonusId, bonusPartId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageBonus(#bonusId)")
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
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageTossup(#tossupId)")
    public Tossup setTossupSubcategory(@Argument String tossupId, @Argument String subcategoryId) {
        return authoringService.setTossupSubcategory(tossupId, subcategoryId);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageBonus(#bonusId)")
    public Bonus setBonusSubcategory(@Argument String bonusId, @Argument String subcategoryId) {
        return authoringService.setBonusSubcategory(bonusId, subcategoryId);
    }

    /* ------------------------------- AI assist ----------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('question:generate') and @packetAuthorizationService.canManage(#packetId)")
    public Packet generateAndAddTossup(@Argument String packetId,
                                       @Argument GenerateTossupInput input,
                                       @Argument Integer order) {
        return authoringService.generateAndAddTossup(packetId, input, order);
    }
}

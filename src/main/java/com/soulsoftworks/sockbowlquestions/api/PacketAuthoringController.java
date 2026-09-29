package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.aikey.UserAiKeyService;
import com.soulsoftworks.sockbowlquestions.api.input.BonusInput;
import com.soulsoftworks.sockbowlquestions.api.input.BonusPartInput;
import com.soulsoftworks.sockbowlquestions.api.input.BonusUpdateInput;
import com.soulsoftworks.sockbowlquestions.api.input.CreatePacketInput;
import com.soulsoftworks.sockbowlquestions.api.input.GenerateTossupInput;
import com.soulsoftworks.sockbowlquestions.api.input.TossupInput;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlquestions.service.PacketAuthoringService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

/**
 * Thin GraphQL mutation layer for packet/question authoring. All orchestration,
 * ordering, and validation live in {@link PacketAuthoringService}.
 *
 * <p>Every mutation that targets an existing packet or its content accepts an optional
 * {@code expectedVersion} (M3, PB-18) and passes it through to the service. Taxonomy
 * creation moved to {@link TaxonomyController}.
 */
@Controller
public class PacketAuthoringController {

    private final PacketAuthoringService authoringService;
    private final UserAiKeyService userAiKeyService;

    public PacketAuthoringController(PacketAuthoringService authoringService,
                                     UserAiKeyService userAiKeyService) {
        this.authoringService = authoringService;
        this.userAiKeyService = userAiKeyService;
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
    public Packet renamePacket(@Argument String id, @Argument String name, @Argument Integer expectedVersion) {
        return authoringService.renamePacket(id, name, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManage(#id)")
    public Packet setPacketDifficulty(@Argument String id, @Argument String difficultyId,
                                      @Argument Integer expectedVersion) {
        return authoringService.setPacketDifficulty(id, difficultyId, expectedVersion);
    }

    /**
     * Publish or unpublish a packet (D2). M3 adds the UI toggle; M2 ships the API.
     * EPHEMERAL can't be set here (it's only created by import-random, D15), and an
     * EPHEMERAL packet can't be changed (canManage is false for it).
     */
    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManage(#id)")
    public Packet setPacketVisibility(@Argument String id, @Argument PacketVisibility visibility,
                                      @Argument Integer expectedVersion) {
        return authoringService.setPacketVisibility(id, visibility, expectedVersion);
    }

    /** manage-any may also delete a game-only (EPHEMERAL) packet, which nobody may edit (D15). */
    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete') and @packetAuthorizationService.canDelete(#id)")
    public boolean deletePacket(@Argument String id, @Argument Integer expectedVersion) {
        return authoringService.deletePacket(id, expectedVersion);
    }

    /* ------------------------------- Tossups ------------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create') and @packetAuthorizationService.canManage(#packetId)")
    public Packet addTossupToPacket(@Argument String packetId,
                                    @Argument TossupInput input,
                                    @Argument Integer order,
                                    @Argument Integer expectedVersion) {
        return authoringService.addTossupToPacket(packetId, input, order, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageTossup(#id)")
    public Tossup updateTossup(@Argument String id, @Argument TossupInput input,
                               @Argument Integer expectedVersion) {
        return authoringService.updateTossup(id, input, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete') and @packetAuthorizationService.canManage(#packetId)")
    public Packet removeTossupFromPacket(@Argument String packetId, @Argument String tossupId,
                                         @Argument Integer expectedVersion) {
        return authoringService.removeTossupFromPacket(packetId, tossupId, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManage(#packetId)")
    public Packet reorderTossup(@Argument String packetId,
                                @Argument String tossupId,
                                @Argument int newOrder,
                                @Argument Integer expectedVersion) {
        return authoringService.reorderTossup(packetId, tossupId, newOrder, expectedVersion);
    }

    /* -------------------------------- Bonuses ------------------------------ */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create') and @packetAuthorizationService.canManage(#packetId)")
    public Packet addBonusToPacket(@Argument String packetId,
                                   @Argument BonusInput input,
                                   @Argument Integer order,
                                   @Argument Integer expectedVersion) {
        return authoringService.addBonusToPacket(packetId, input, order, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageBonus(#id)")
    public Bonus updateBonus(@Argument String id, @Argument BonusUpdateInput input,
                             @Argument Integer expectedVersion) {
        return authoringService.updateBonus(id, input, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete') and @packetAuthorizationService.canManage(#packetId)")
    public Packet removeBonusFromPacket(@Argument String packetId, @Argument String bonusId,
                                        @Argument Integer expectedVersion) {
        return authoringService.removeBonusFromPacket(packetId, bonusId, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManage(#packetId)")
    public Packet reorderBonus(@Argument String packetId,
                               @Argument String bonusId,
                               @Argument int newOrder,
                               @Argument Integer expectedVersion) {
        return authoringService.reorderBonus(packetId, bonusId, newOrder, expectedVersion);
    }

    /* ------------------------------ Bonus parts ---------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create') and @packetAuthorizationService.canManageBonus(#bonusId)")
    public Bonus addBonusPart(@Argument String bonusId,
                              @Argument BonusPartInput input,
                              @Argument Integer order,
                              @Argument Integer expectedVersion) {
        return authoringService.addBonusPart(bonusId, input, order, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageBonus(#bonusId)")
    public Bonus updateBonusPart(@Argument String bonusId,
                                 @Argument String bonusPartId,
                                 @Argument BonusPartInput input,
                                 @Argument Integer expectedVersion) {
        return authoringService.updateBonusPart(bonusId, bonusPartId, input, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:delete') and @packetAuthorizationService.canManageBonus(#bonusId)")
    public Bonus removeBonusPart(@Argument String bonusId, @Argument String bonusPartId,
                                 @Argument Integer expectedVersion) {
        return authoringService.removeBonusPart(bonusId, bonusPartId, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageBonus(#bonusId)")
    public Bonus reorderBonusPart(@Argument String bonusId,
                                  @Argument String bonusPartId,
                                  @Argument int newOrder,
                                  @Argument Integer expectedVersion) {
        return authoringService.reorderBonusPart(bonusId, bonusPartId, newOrder, expectedVersion);
    }

    /* ------------------------------ Subcategory ---------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageTossup(#tossupId)")
    public Tossup setTossupSubcategory(@Argument String tossupId, @Argument String subcategoryId,
                                       @Argument Integer expectedVersion) {
        return authoringService.setTossupSubcategory(tossupId, subcategoryId, expectedVersion);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManageBonus(#bonusId)")
    public Bonus setBonusSubcategory(@Argument String bonusId, @Argument String subcategoryId,
                                     @Argument Integer expectedVersion) {
        return authoringService.setBonusSubcategory(bonusId, subcategoryId, expectedVersion);
    }

    /* ------------------------------- AI assist ----------------------------- */

    @MutationMapping
    @PreAuthorize("hasAuthority('question:generate') and @packetAuthorizationService.canManage(#packetId)")
    public Packet generateAndAddTossup(@Argument String packetId,
                                       @Argument GenerateTossupInput input,
                                       @Argument Integer order,
                                       @Argument Integer expectedVersion) {
        // No apiKey in the input: use the caller's saved Claude key, if any.
        AiRequestContext savedKey = null;
        if (input == null || input.apiKey() == null || input.apiKey().isBlank()) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Jwt jwt = auth != null && auth.getPrincipal() instanceof Jwt j ? j : null;
            savedKey = userAiKeyService.resolveContext(AuthenticatedUser.fromJwt(jwt).keycloakId(),
                    input == null ? null : input.model()).orElse(null);
        }
        return authoringService.generateAndAddTossup(packetId, input, order, expectedVersion, savedKey);
    }
}

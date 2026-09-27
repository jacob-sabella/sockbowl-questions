package com.soulsoftworks.sockbowlquestions.dto;

import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;

/**
 * GraphQL {@code PacketSummary} (plan 3.1.9, 3.1.11): an answer-free list projection.
 * It never carries question text or answers, so it is safe to return for any packet the
 * caller may {@link com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy#canSee
 * see}, whether or not they may read it in full.
 *
 * @param version   the optimistic-lock version; null in storage reads as 0 (same rule as
 *                  {@code Packet.version})
 * @param playable  {@code true} exactly when {@code PacketValidator} would report no
 *                  ERROR issue for this packet
 */
public record PacketSummaryDto(String id, String name, Difficulty difficulty, PacketOwnerDto owner,
                               PacketVisibility visibility, int version, int tossupCount,
                               int bonusCount, boolean playable) {
}

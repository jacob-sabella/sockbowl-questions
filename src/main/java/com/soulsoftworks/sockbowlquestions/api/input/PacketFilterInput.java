package com.soulsoftworks.sockbowlquestions.api.input;

import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;

/**
 * Input for {@code packets} (M3 Q5, plan 3.1.9/3.1.11). Every field is optional; a null
 * field applies no restriction. {@code visibility = EPHEMERAL} is schema-legal but always
 * yields an empty page: {@code PacketSummaryRepository} excludes game-only packets from
 * every list regardless of the filter (D15).
 *
 * @param mine           only the caller's own packets; with no authenticated caller this
 *                       yields an empty page rather than an error
 * @param nameContains   case-insensitive substring match on {@code Packet.name}
 * @param difficultyId   restrict to one difficulty
 * @param visibility     restrict to one visibility (still filtered by the caller's read rights)
 * @param playableOnly   only packets {@code PacketValidator} would call playable
 */
public record PacketFilterInput(Boolean mine, String nameContains, String difficultyId,
                                PacketVisibility visibility, Boolean playableOnly) {
}

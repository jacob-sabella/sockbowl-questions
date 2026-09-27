package com.soulsoftworks.sockbowlquestions.api.input;

import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;

/**
 * Filter for the paginated {@code packets} summary query (PB-19). Every field is
 * optional; null means "don't filter on this". {@code mine} needs an authenticated
 * caller (anonymous gets an empty page). The caller's read rights (D2) are always
 * applied on top of these filters.
 *
 * <p>Game-only visibilities ({@code EPHEMERAL}, D15) are never listed, whoever asks
 * ({@code PacketReadPolicy.unlistedVisibilities()}), so {@code visibility: EPHEMERAL}
 * yields an empty page rather than game-only packets. A stored null visibility counts as
 * {@code PUBLISHED} ({@code PacketReadPolicy.legacyVisibility()}).
 */
public record PacketFilter(Boolean mine,
                           String nameContains,
                           String difficultyId,
                           PacketVisibility visibility,
                           Boolean playableOnly) {

    public boolean isMine() {
        return mine != null && mine;
    }

    public boolean isPlayableOnly() {
        return playableOnly != null && playableOnly;
    }
}

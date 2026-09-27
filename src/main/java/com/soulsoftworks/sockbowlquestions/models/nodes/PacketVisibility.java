package com.soulsoftworks.sockbowlquestions.models.nodes;

/**
 * Who may read a packet (D2). Stored on the {@code Packet} node as the enum name.
 *
 * <p>Each value says whether it is <em>publicly readable</em>: anyone, including
 * anonymous callers and guests, may see a publicly readable packet as an
 * answer-free projection. A packet that is not publicly readable is visible only
 * to callers who may read it in full (its owner, {@code packet:manage-any},
 * the game service token with {@code packet:read-answers}).
 *
 * <p>Read logic never switches on the concrete values. It asks
 * {@link #isPubliclyReadable()} (and {@link #effective(PacketVisibility)} for legacy
 * nodes) and {@link #isGameOnly()}, so a value can be appended without changing
 * the projection or listing code.
 *
 * <p>A <em>game-only</em> value ({@link #EPHEMERAL}, D15) is never publicly readable
 * and additionally never listed or searched, never editable, and readable in full
 * only by the game service token ({@code packet:read-answers}).
 */
public enum PacketVisibility {
    /** Work in progress: only full readers see it. The default for new packets. */
    DRAFT(false),
    /** Listed and readable by everyone, with answers stripped for non-full readers. */
    PUBLISHED(true),
    /**
     * A bank packet generated through {@code import-random} by a caller without
     * {@code packet:create} (guests and players, D15). Ownerless, game-only, and
     * deleted by the TTL cleanup job ({@code sockbowl.packet.ephemeral-ttl}).
     */
    EPHEMERAL(false, true);

    private final boolean publiclyReadable;
    private final boolean gameOnly;

    PacketVisibility(boolean publiclyReadable) {
        this(publiclyReadable, false);
    }

    PacketVisibility(boolean publiclyReadable, boolean gameOnly) {
        this.publiclyReadable = publiclyReadable;
        this.gameOnly = gameOnly;
    }

    /** True when callers without full-read rights may still see the (answer-free) packet. */
    public boolean isPubliclyReadable() {
        return publiclyReadable;
    }

    /**
     * True for a packet that exists only to be played (D15): it is left out of every
     * list and search, nobody may edit it (only {@code packet:manage-any} may delete
     * it), and only the game service token may read it in full.
     */
    public boolean isGameOnly() {
        return gameOnly;
    }

    /** True when the packet may appear in list and search results (for callers who can see it). */
    public boolean isListed() {
        return !gameOnly;
    }

    /**
     * The visibility to apply for a stored value. Legacy nodes created before
     * visibility existed have none, and count as {@link #PUBLISHED} (plan P2).
     */
    public static PacketVisibility effective(PacketVisibility stored) {
        return stored == null ? PUBLISHED : stored;
    }

    /** The default for newly created packets (authored, imported or generated). */
    public static PacketVisibility defaultForNewPackets() {
        return DRAFT;
    }
}

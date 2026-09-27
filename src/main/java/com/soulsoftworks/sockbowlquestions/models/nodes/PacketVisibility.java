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
 * nodes), so a later value (for example an {@code EPHEMERAL}, game-only packet)
 * can be appended without changing the projection or listing code.
 */
public enum PacketVisibility {
    /** Work in progress: only full readers see it. The default for new packets. */
    DRAFT(false),
    /** Listed and readable by everyone, with answers stripped for non-full readers. */
    PUBLISHED(true);

    private final boolean publiclyReadable;

    PacketVisibility(boolean publiclyReadable) {
        this.publiclyReadable = publiclyReadable;
    }

    /** True when callers without full-read rights may still see the (answer-free) packet. */
    public boolean isPubliclyReadable() {
        return publiclyReadable;
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

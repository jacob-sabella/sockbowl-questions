package com.soulsoftworks.sockbowlquestions.models.nodes;

/**
 * How a piece of content (a {@link Packet}, {@link Tossup}, {@link Bonus} or
 * {@link BonusPart}) came to exist (D13, M4-PV-01). Stored on the node as the enum name.
 *
 * <p>Set explicitly by application code on every write path that creates or edits
 * content; unlike {@code createdBy}/{@code createdAt}/{@code lastModifiedBy}/
 * {@code lastModifiedAt} (which SDN auditing populates automatically), {@code source}
 * is a plain field with no framework support.
 */
public enum ContentSource {
    /** Created or last edited by hand through the packet-builder authoring API. */
    AUTHORED,
    /** Copied from the local qbreader question bank via {@code import-random}. */
    QBREADER_IMPORT,
    /** Written by an LLM, either the server's key or the caller's own ({@code aiModel} records which model). */
    AI_GENERATED,
    /** Reserved for M3's plaintext packet import (PB-04); not produced by any M4 path. */
    TEXT_IMPORT,
    /** Reserved for M3's {@code clonePacket} (PB-18); not produced by any M4 path. */
    CLONED
}

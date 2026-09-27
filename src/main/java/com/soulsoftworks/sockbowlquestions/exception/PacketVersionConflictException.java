package com.soulsoftworks.sockbowlquestions.exception;

/**
 * Optimistic-lock failure (PB-18): the caller sent an {@code expectedVersion} that no
 * longer matches the packet's stored version. Reported to GraphQL clients as
 * {@code CONFLICT} with the extensions {@code packetId} and {@code currentVersion}, so
 * the builder can offer "reload" without a second round trip.
 */
public class PacketVersionConflictException extends RuntimeException {

    private final String packetId;
    private final Long expectedVersion;
    private final Long currentVersion;

    public PacketVersionConflictException(String packetId, Long expectedVersion, Long currentVersion) {
        super("Packet " + packetId + " changed elsewhere (expected version " + expectedVersion
                + ", current version " + currentVersion + "). Reload to see the latest version.");
        this.packetId = packetId;
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
    }

    public String getPacketId() {
        return packetId;
    }

    public Long getExpectedVersion() {
        return expectedVersion;
    }

    public Long getCurrentVersion() {
        return currentVersion;
    }
}

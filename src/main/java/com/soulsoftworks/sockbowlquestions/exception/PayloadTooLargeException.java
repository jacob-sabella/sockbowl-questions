package com.soulsoftworks.sockbowlquestions.exception;

/**
 * An input exceeded a configured size limit, such as a plaintext import larger than
 * {@code sockbowl.packet.import.max-bytes}. Reported to GraphQL clients as
 * {@code PAYLOAD_TOO_LARGE} with the extension {@code limitBytes}.
 */
public class PayloadTooLargeException extends RuntimeException {

    private final long limitBytes;

    public PayloadTooLargeException(long limitBytes) {
        this(limitBytes, "Input exceeds the limit of " + limitBytes + " bytes");
    }

    public PayloadTooLargeException(long limitBytes, String message) {
        super(message);
        this.limitBytes = limitBytes;
    }

    public long getLimitBytes() {
        return limitBytes;
    }
}

package com.soulsoftworks.sockbowlquestions.exception;

/**
 * Structural or field validation of a packet, tossup, bonus, taxonomy entry or import
 * payload failed (PB-11). Reported to GraphQL clients as {@code VALIDATION_FAILED} with
 * the extension {@code field} naming the offending input (for example {@code "name"},
 * {@code "question"} or {@code "parts"}), so the UI can attach the message to it.
 */
public class ValidationFailedException extends RuntimeException {

    private final String field;

    public ValidationFailedException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}

package com.soulsoftworks.sockbowlquestions.exception;

/**
 * Exception thrown when API request validation fails.
 * Typically results in a 400 Bad Request response.
 */
public class InvalidApiRequestException extends RuntimeException {
    public InvalidApiRequestException(String message) {
        super(message);
    }
}

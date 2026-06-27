package com.soulsoftworks.sockbowlquestions.exception;

/**
 * Thrown when an authoring operation references an entity that does not exist.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException of(String type, String id) {
        return new ResourceNotFoundException(type + " not found: " + id);
    }
}

package com.soulsoftworks.sockbowlquestions.aikey;

/** Saved keys need {@code SOCKBOWL_AI_KEY_ENCRYPTION_KEY}; rendered as 503. */
public class SavedKeysDisabledException extends RuntimeException {
    public SavedKeysDisabledException() {
        super("Saved API keys aren't enabled on this server");
    }
}

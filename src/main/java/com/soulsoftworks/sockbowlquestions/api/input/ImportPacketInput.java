package com.soulsoftworks.sockbowlquestions.api.input;

/**
 * Input for {@code importPacket} (D5 plaintext import). {@code dryRun} defaults to true
 * in the schema: the server parses and previews without writing. {@code skipInvalid}
 * lets a commit go through while some items have ERROR issues (those items are dropped).
 * {@code name} overrides the parsed {@code suggestedName}; {@code difficultyId} is optional.
 */
public record ImportPacketInput(String text,
                                String name,
                                String difficultyId,
                                Boolean dryRun,
                                Boolean skipInvalid) {

    /** True unless the caller explicitly asked to commit. */
    public boolean isDryRun() {
        return dryRun == null || dryRun;
    }

    public boolean isSkipInvalid() {
        return skipInvalid != null && skipInvalid;
    }
}

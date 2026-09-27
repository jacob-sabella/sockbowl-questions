package com.soulsoftworks.sockbowlquestions.packetio;

import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;

/**
 * One parsed tossup (GraphQL {@code ParsedTossup}, plan 3.1.11). {@link #categoryTag()}
 * is the raw {@code <...>} tag text kept verbatim for the preview; {@link #subcategory()}
 * is null from {@link PlaintextPacketParser} (which never touches the database) and is
 * filled in afterwards by {@code PacketImportService} once the tag is resolved against
 * existing taxonomy, via {@link #withSubcategory}.
 */
public record ParsedTossup(Integer number, int line, String question, String answer,
                           String categoryTag, Subcategory subcategory) {

    public ParsedTossup withSubcategory(Subcategory resolved) {
        return new ParsedTossup(number, line, question, answer, categoryTag, resolved);
    }
}

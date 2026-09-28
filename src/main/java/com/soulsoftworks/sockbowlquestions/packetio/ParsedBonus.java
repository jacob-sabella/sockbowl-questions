package com.soulsoftworks.sockbowlquestions.packetio;

import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;

import java.util.List;

/**
 * One parsed bonus (GraphQL {@code ParsedBonus}, plan 3.1.11). See {@link ParsedTossup}
 * for {@code categoryTag}/{@code subcategory} (the same two-phase resolution applies).
 */
public record ParsedBonus(Integer number, int line, String preamble, List<ParsedBonusPart> parts,
                          String categoryTag, Subcategory subcategory) {

    public ParsedBonus withSubcategory(Subcategory resolved) {
        return new ParsedBonus(number, line, preamble, parts, categoryTag, resolved);
    }
}

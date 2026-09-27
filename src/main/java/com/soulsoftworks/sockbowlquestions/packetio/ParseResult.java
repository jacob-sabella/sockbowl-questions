package com.soulsoftworks.sockbowlquestions.packetio;

import java.util.List;

/**
 * The result of {@link PlaintextPacketParser#parse}: every item the grammar accepted
 * (already dropped items are represented only by an {@code ERROR} issue, never returned
 * here), plus every diagnostic. {@code suggestedName} is the first non-blank preface
 * line (text before the first item), or null when there wasn't one.
 */
public record ParseResult(String suggestedName, List<ParsedTossup> tossups, List<ParsedBonus> bonuses,
                          List<ParseIssue> issues) {
}

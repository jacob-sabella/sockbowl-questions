package com.soulsoftworks.sockbowlquestions.packetio;

/**
 * One parsed bonus part (GraphQL {@code ParsedBonusPart}, plan 3.1.11). The part's
 * value (e.g. 10) and difficulty letter are read by the grammar but not modeled
 * (plan 3.1.8): a non-default value only produces a {@link ParseIssue#PART_VALUE_NOT_STORED}
 * info issue.
 */
public record ParsedBonusPart(int line, String question, String answer) {
}

package com.soulsoftworks.sockbowlquestions.packetio;

/**
 * Severity of a plaintext-import diagnostic (D5, plan 3.1.8), matching the GraphQL
 * {@code IssueSeverity} enum used by {@code ImportIssue}. Spring GraphQL coerces an enum
 * output by matching the constant name, so this type only needs the same three names as
 * the schema; it is independent of {@code PacketValidator}'s own {@code IssueSeverity}
 * (Q2), which serves {@code ValidationIssue} instead.
 */
public enum IssueSeverity {
    ERROR,
    WARNING,
    INFO
}

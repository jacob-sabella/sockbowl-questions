package com.soulsoftworks.sockbowlquestions.packetio;

/**
 * One diagnostic from {@link PlaintextPacketParser}, anchored to a (1-based) source
 * line where known. {@code code} is an internal, stable identifier tests and callers
 * can match on; the GraphQL {@code ImportIssue} type exposes only
 * {@code severity}/{@code line}/{@code message} (see {@code dto.ImportIssueDto}).
 */
public record ParseIssue(IssueSeverity severity, String code, Integer line, String message) {

    /* --------------------------------- errors (item dropped) -------------------------------- */
    public static final String MISSING_ANSWER = "MISSING_ANSWER";
    public static final String MISSING_QUESTION = "MISSING_QUESTION";
    public static final String EMPTY_BONUS = "EMPTY_BONUS";
    public static final String PART_MISSING_ANSWER = "PART_MISSING_ANSWER";
    public static final String FIELD_TOO_LONG = "FIELD_TOO_LONG";
    public static final String TOO_MANY_TOSSUPS = "TOO_MANY_TOSSUPS";
    public static final String TOO_MANY_BONUSES = "TOO_MANY_BONUSES";
    public static final String TOO_MANY_PARTS = "TOO_MANY_PARTS";

    /* --------------------------------- warnings (item kept) ---------------------------------- */
    public static final String UNEXPECTED_PART_COUNT = "UNEXPECTED_PART_COUNT";
    public static final String NUMBERING_GAP = "NUMBERING_GAP";
    public static final String DUPLICATE_NUMBER = "DUPLICATE_NUMBER";
    public static final String MORE_BONUSES_THAN_TOSSUPS = "MORE_BONUSES_THAN_TOSSUPS";
    public static final String UNKNOWN_CATEGORY_TAG = "UNKNOWN_CATEGORY_TAG";
    public static final String EMPTY_PREAMBLE = "EMPTY_PREAMBLE";
    /** The suggested packet name (first preface line) was cut to {@code name-max}. */
    public static final String NAME_TRUNCATED = "NAME_TRUNCATED";

    /* ------------------------------------------ info ----------------------------------------- */
    public static final String PART_VALUE_NOT_STORED = "PART_VALUE_NOT_STORED";
    public static final String TIEBREAKER_AS_TOSSUP = "TIEBREAKER_AS_TOSSUP";
    public static final String PREFACE_IGNORED = "PREFACE_IGNORED";

    public static ParseIssue error(String code, Integer line, String message) {
        return new ParseIssue(IssueSeverity.ERROR, code, line, message);
    }

    public static ParseIssue warning(String code, Integer line, String message) {
        return new ParseIssue(IssueSeverity.WARNING, code, line, message);
    }

    public static ParseIssue info(String code, Integer line, String message) {
        return new ParseIssue(IssueSeverity.INFO, code, line, message);
    }

    public boolean isError() {
        return severity == IssueSeverity.ERROR;
    }
}

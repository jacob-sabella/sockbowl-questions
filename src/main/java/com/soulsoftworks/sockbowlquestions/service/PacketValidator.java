package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.config.PacketLimitsProperties;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.ValidationFailedException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Packet validation (M3, PB-11, plan 3.1.5). Pure: it never touches a repository, so
 * {@link #validate(Packet)} costs nothing beyond the packet graph already loaded.
 *
 * <p>Two jobs:
 * <ul>
 *   <li><b>Write-time guards</b> used by {@link PacketAuthoringService}: field length limits
 *       ({@link #requireText}, {@link #optionalText}) and the structural caps (tossups,
 *       bonuses and parts per bonus, and the min-parts floor). Violations throw
 *       {@link ValidationFailedException} ({@code VALIDATION_FAILED}, with the offending
 *       field). A blank required field keeps the pre-M3 {@link InvalidApiRequestException}
 *       ({@code BAD_REQUEST}).</li>
 *   <li><b>Playability</b> ({@link #validate(Packet)}): a {@link PacketValidation} with
 *       ERROR/WARNING/INFO issues. {@code playable} is true exactly when there is no ERROR.</li>
 * </ul>
 *
 * <p>Issue messages describe structure only (positions and counts). They never quote
 * question or answer text, because a caller may see the validation of a packet whose
 * answers are redacted for them.
 */
@Component
public class PacketValidator {

    /** Field names reported in {@link ValidationFailedException#getField()}. */
    public static final String FIELD_NAME = "name";
    public static final String FIELD_QUESTION = "question";
    public static final String FIELD_ANSWER = "answer";
    public static final String FIELD_PREAMBLE = "preamble";
    public static final String FIELD_PARTS = "parts";
    public static final String FIELD_TOSSUPS = "tossups";
    public static final String FIELD_BONUSES = "bonuses";

    /** Issue codes of {@link #validate(Packet)}. */
    public static final String NO_TOSSUPS = "NO_TOSSUPS";
    public static final String BONUS_WITHOUT_PARTS = "BONUS_WITHOUT_PARTS";
    public static final String MORE_BONUSES_THAN_TOSSUPS = "MORE_BONUSES_THAN_TOSSUPS";
    public static final String BONUS_PART_COUNT = "BONUS_PART_COUNT";
    public static final String EMPTY_PREAMBLE = "EMPTY_PREAMBLE";
    public static final String MISSING_SUBCATEGORY = "MISSING_SUBCATEGORY";

    /** The D7 "expected" part count; any other count is a warning, not an error. */
    static final int EXPECTED_PARTS_PER_BONUS = 3;

    private final PacketLimitsProperties.Limits limits;

    public PacketValidator(PacketLimitsProperties properties) {
        this.limits = properties.getLimits();
    }

    public PacketLimitsProperties.Limits limits() {
        return limits;
    }

    /* ---------------------------- field limits ----------------------------- */

    /**
     * A required text field: trimmed, not blank ({@link InvalidApiRequestException}) and at
     * most {@code max} characters ({@link ValidationFailedException} on {@code field}).
     *
     * @param label human-readable name used in the message, e.g. "Tossup question"
     */
    public String requireText(String value, String field, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new InvalidApiRequestException(label + " must not be blank");
        }
        return checkLength(value.trim(), field, label, max);
    }

    /** An optional text field: null stays null, otherwise trimmed and length-checked. */
    public String optionalText(String value, String field, String label, int max) {
        if (value == null) {
            return null;
        }
        return checkLength(value.trim(), field, label, max);
    }

    public String packetName(String value) {
        return requireText(value, FIELD_NAME, "Packet name", limits.getNameMax());
    }

    public String question(String value, String label) {
        return requireText(value, FIELD_QUESTION, label, limits.getQuestionMax());
    }

    public String answer(String value, String label) {
        return requireText(value, FIELD_ANSWER, label, limits.getAnswerMax());
    }

    public String preamble(String value) {
        return optionalText(value, FIELD_PREAMBLE, "Bonus preamble", limits.getPreambleMax());
    }

    private static String checkLength(String trimmed, String field, String label, int max) {
        if (trimmed.length() > max) {
            throw new ValidationFailedException(field, label + " exceeds " + max + " characters");
        }
        return trimmed;
    }

    /* --------------------------- structural caps --------------------------- */

    /** Rejects adding a tossup to a packet that already holds {@code currentCount}. */
    public void checkCanAddTossup(int currentCount) {
        if (currentCount >= limits.getMaxTossups()) {
            throw new ValidationFailedException(FIELD_TOSSUPS,
                    "A packet can hold at most " + limits.getMaxTossups() + " tossups");
        }
    }

    /** Rejects adding a bonus to a packet that already holds {@code currentCount}. */
    public void checkCanAddBonus(int currentCount) {
        if (currentCount >= limits.getMaxBonuses()) {
            throw new ValidationFailedException(FIELD_BONUSES,
                    "A packet can hold at most " + limits.getMaxBonuses() + " bonuses");
        }
    }

    /** The parts a new bonus is created with: at least the floor, at most the cap. */
    public void checkNewBonusParts(int partCount) {
        if (partCount < Math.max(1, limits.getMinPartsPerBonus())) {
            throw new ValidationFailedException(FIELD_PARTS, minPartsMessage());
        }
        if (partCount > limits.getMaxPartsPerBonus()) {
            throw new ValidationFailedException(FIELD_PARTS,
                    "A bonus can have at most " + limits.getMaxPartsPerBonus() + " parts");
        }
    }

    /** Rejects adding a part to a bonus that already has {@code currentCount}. */
    public void checkCanAddPart(int currentCount) {
        if (currentCount >= limits.getMaxPartsPerBonus()) {
            throw new ValidationFailedException(FIELD_PARTS,
                    "A bonus can have at most " + limits.getMaxPartsPerBonus() + " parts");
        }
    }

    /** Rejects removing a part when the bonus would drop below the floor. */
    public void checkCanRemovePart(int currentCount) {
        if (currentCount - 1 < Math.max(1, limits.getMinPartsPerBonus())) {
            throw new ValidationFailedException(FIELD_PARTS, minPartsMessage() + "; delete the bonus instead");
        }
    }

    private String minPartsMessage() {
        int min = Math.max(1, limits.getMinPartsPerBonus());
        return min == 1 ? "A bonus needs at least one part" : "A bonus needs at least " + min + " parts";
    }

    /* ----------------------------- playability ----------------------------- */

    /**
     * Playability and D7 warnings for an already-loaded packet graph (plan 3.1.5).
     * Positions in messages are 1-based in reading order.
     */
    public PacketValidation validate(Packet packet) {
        List<ValidationIssue> issues = new ArrayList<>();
        List<Tossup> tossups = packet == null ? List.of() : orderedTossups(packet);
        List<Bonus> bonuses = packet == null ? List.of() : orderedBonuses(packet);

        if (tossups.isEmpty()) {
            issues.add(ValidationIssue.error(NO_TOSSUPS, "The packet has no tossups", null, null));
        }
        if (bonuses.size() > tossups.size()) {
            issues.add(ValidationIssue.warning(MORE_BONUSES_THAN_TOSSUPS,
                    "The packet has " + bonuses.size() + " bonuses but only " + tossups.size()
                            + " tossups; bonuses past tossup " + tossups.size() + " are never read",
                    null, null));
        }
        for (int i = 0; i < tossups.size(); i++) {
            Tossup tossup = tossups.get(i);
            if (tossup.getSubcategory() == null) {
                issues.add(ValidationIssue.info(MISSING_SUBCATEGORY,
                        "Tossup " + (i + 1) + " has no subcategory", tossup.getId(), null));
            }
        }
        for (int i = 0; i < bonuses.size(); i++) {
            Bonus bonus = bonuses.get(i);
            int number = i + 1;
            int parts = bonus.getBonusParts() == null ? 0
                    : (int) bonus.getBonusParts().stream().filter(Objects::nonNull).count();
            if (parts == 0) {
                issues.add(ValidationIssue.error(BONUS_WITHOUT_PARTS,
                        "Bonus " + number + " has no parts", null, bonus.getId()));
            } else if (parts != EXPECTED_PARTS_PER_BONUS) {
                issues.add(ValidationIssue.warning(BONUS_PART_COUNT,
                        "Bonus " + number + " has " + parts + " part" + (parts == 1 ? "" : "s")
                                + "; " + EXPECTED_PARTS_PER_BONUS + " is standard",
                        null, bonus.getId()));
            }
            if (bonus.getPreamble() == null || bonus.getPreamble().isBlank()) {
                issues.add(ValidationIssue.warning(EMPTY_PREAMBLE,
                        "Bonus " + number + " has no preamble", null, bonus.getId()));
            }
            if (bonus.getSubcategory() == null) {
                issues.add(ValidationIssue.info(MISSING_SUBCATEGORY,
                        "Bonus " + number + " has no subcategory", null, bonus.getId()));
            }
        }

        boolean playable = issues.stream().noneMatch(issue -> issue.severity() == IssueSeverity.ERROR);
        return new PacketValidation(playable, tossups.size(), bonuses.size(), List.copyOf(issues));
    }

    private static List<Tossup> orderedTossups(Packet packet) {
        if (packet.getTossups() == null) {
            return List.of();
        }
        return packet.getTossups().stream()
                .filter(rel -> rel != null && rel.getTossup() != null)
                .sorted(Comparator.comparingInt(rel -> orderOrMax(rel.getOrder())))
                .map(ContainsTossup::getTossup)
                .toList();
    }

    private static List<Bonus> orderedBonuses(Packet packet) {
        if (packet.getBonuses() == null) {
            return List.of();
        }
        return packet.getBonuses().stream()
                .filter(rel -> rel != null && rel.getBonus() != null)
                .sorted(Comparator.comparingInt(rel -> orderOrMax(rel.getOrder())))
                .map(ContainsBonus::getBonus)
                .toList();
    }

    private static int orderOrMax(Integer order) {
        return order == null ? Integer.MAX_VALUE : order;
    }

    /* ------------------------------- results ------------------------------- */

    /** GraphQL {@code IssueSeverity}. */
    public enum IssueSeverity { ERROR, WARNING, INFO }

    /** GraphQL {@code ValidationIssue}. {@code tossupId}/{@code bonusId} locate the item, when there is one. */
    public record ValidationIssue(IssueSeverity severity, String code, String message,
                                  String tossupId, String bonusId) {
        static ValidationIssue error(String code, String message, String tossupId, String bonusId) {
            return new ValidationIssue(IssueSeverity.ERROR, code, message, tossupId, bonusId);
        }

        static ValidationIssue warning(String code, String message, String tossupId, String bonusId) {
            return new ValidationIssue(IssueSeverity.WARNING, code, message, tossupId, bonusId);
        }

        static ValidationIssue info(String code, String message, String tossupId, String bonusId) {
            return new ValidationIssue(IssueSeverity.INFO, code, message, tossupId, bonusId);
        }
    }

    /** GraphQL {@code PacketValidation}. */
    public record PacketValidation(boolean playable, int tossupCount, int bonusCount,
                                   List<ValidationIssue> issues) {
    }
}

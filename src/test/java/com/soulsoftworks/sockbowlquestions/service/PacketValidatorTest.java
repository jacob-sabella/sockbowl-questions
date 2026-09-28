package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.config.PacketLimitsProperties;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.ValidationFailedException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.service.PacketValidator.IssueSeverity;
import com.soulsoftworks.sockbowlquestions.service.PacketValidator.PacketValidation;
import com.soulsoftworks.sockbowlquestions.service.PacketValidator.ValidationIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link PacketValidator}: each issue code of plan 3.1.5, the playable rule and the write guards. */
class PacketValidatorTest {

    private PacketLimitsProperties properties;
    private PacketValidator validator;
    private final Subcategory sub = subcategory();

    @BeforeEach
    void setUp() {
        properties = new PacketLimitsProperties();
        validator = new PacketValidator(properties);
    }

    /* ------------------------------- fixtures ------------------------------- */

    private static Subcategory subcategory() {
        Subcategory s = new Subcategory();
        s.setId("s1");
        s.setName("Biology");
        return s;
    }

    private Tossup tossup(String id, Subcategory subcategory) {
        Tossup t = Tossup.builder().question("secret question " + id).answer("secret answer " + id)
                .subcategory(subcategory).build();
        t.setId(id);
        return t;
    }

    private Bonus bonus(String id, int parts, String preamble, Subcategory subcategory) {
        Bonus b = new Bonus();
        b.setId(id);
        b.setPreamble(preamble);
        b.setSubcategory(subcategory);
        List<HasBonusPart> rels = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            BonusPart part = new BonusPart();
            part.setId(id + "-p" + i);
            part.setQuestion("q");
            part.setAnswer("secret part answer");
            rels.add(new HasBonusPart(i, part));
        }
        b.setBonusParts(rels);
        return b;
    }

    private Packet packet(List<Tossup> tossups, List<Bonus> bonuses) {
        Packet p = new Packet();
        p.setId("p");
        List<ContainsTossup> t = new ArrayList<>();
        for (int i = 0; i < tossups.size(); i++) {
            t.add(ContainsTossup.builder().order(i).tossup(tossups.get(i)).build());
        }
        List<ContainsBonus> b = new ArrayList<>();
        for (int i = 0; i < bonuses.size(); i++) {
            b.add(new ContainsBonus(i, bonuses.get(i)));
        }
        p.setTossups(t);
        p.setBonuses(b);
        return p;
    }

    private static List<String> codes(PacketValidation v) {
        return v.issues().stream().map(ValidationIssue::code).toList();
    }

    private static ValidationIssue only(PacketValidation v, String code) {
        List<ValidationIssue> matching = v.issues().stream().filter(i -> i.code().equals(code)).toList();
        assertThat(matching).as(code).hasSize(1);
        return matching.get(0);
    }

    /* ------------------------------ playability ----------------------------- */

    @Test
    void wellFormedPacket_isPlayableWithNoIssues() {
        PacketValidation v = validator.validate(packet(
                List.of(tossup("t1", sub), tossup("t2", sub)),
                List.of(bonus("b1", 3, "For 10 points each:", sub))));
        assertThat(v.playable()).isTrue();
        assertThat(v.tossupCount()).isEqualTo(2);
        assertThat(v.bonusCount()).isEqualTo(1);
        assertThat(v.issues()).isEmpty();
    }

    @Test
    void noTossups_isAnError_andNotPlayable() {
        PacketValidation v = validator.validate(packet(List.of(), List.of()));
        assertThat(v.playable()).isFalse();
        ValidationIssue issue = only(v, PacketValidator.NO_TOSSUPS);
        assertThat(issue.severity()).isEqualTo(IssueSeverity.ERROR);
    }

    @Test
    void nullRelationshipLists_countAsEmpty() {
        Packet p = new Packet();
        p.setId("p");
        PacketValidation v = validator.validate(p);
        assertThat(v.playable()).isFalse();
        assertThat(v.tossupCount()).isZero();
        assertThat(codes(v)).containsExactly(PacketValidator.NO_TOSSUPS);
    }

    @Test
    void bonusWithoutParts_isAnError_withTheBonusId() {
        PacketValidation v = validator.validate(packet(
                List.of(tossup("t1", sub)), List.of(bonus("b1", 0, "P", sub))));
        assertThat(v.playable()).isFalse();
        ValidationIssue issue = only(v, PacketValidator.BONUS_WITHOUT_PARTS);
        assertThat(issue.severity()).isEqualTo(IssueSeverity.ERROR);
        assertThat(issue.bonusId()).isEqualTo("b1");
        assertThat(codes(v)).doesNotContain(PacketValidator.BONUS_PART_COUNT);
    }

    @Test
    void moreBonusesThanTossups_isAWarning_andStillPlayable() {
        PacketValidation v = validator.validate(packet(
                List.of(tossup("t1", sub)),
                List.of(bonus("b1", 3, "P", sub), bonus("b2", 3, "P", sub))));
        assertThat(v.playable()).isTrue();
        assertThat(only(v, PacketValidator.MORE_BONUSES_THAN_TOSSUPS).severity()).isEqualTo(IssueSeverity.WARNING);
    }

    @Test
    void partCountOtherThanThree_isAWarning() {
        PacketValidation v = validator.validate(packet(
                List.of(tossup("t1", sub), tossup("t2", sub)),
                List.of(bonus("b1", 2, "P", sub), bonus("b2", 4, "P", sub))));
        assertThat(v.playable()).isTrue();
        List<ValidationIssue> partCount = v.issues().stream()
                .filter(i -> i.code().equals(PacketValidator.BONUS_PART_COUNT)).toList();
        assertThat(partCount).extracting(ValidationIssue::bonusId).containsExactly("b1", "b2");
        assertThat(partCount).allSatisfy(i -> assertThat(i.severity()).isEqualTo(IssueSeverity.WARNING));
    }

    @Test
    void emptyPreamble_isAWarning() {
        PacketValidation v = validator.validate(packet(
                List.of(tossup("t1", sub)), List.of(bonus("b1", 3, "  ", sub))));
        assertThat(v.playable()).isTrue();
        assertThat(only(v, PacketValidator.EMPTY_PREAMBLE).severity()).isEqualTo(IssueSeverity.WARNING);
    }

    @Test
    void missingSubcategory_isInfo_forTossupsAndBonuses() {
        PacketValidation v = validator.validate(packet(
                List.of(tossup("t1", null)), List.of(bonus("b1", 3, "P", null))));
        assertThat(v.playable()).isTrue();
        List<ValidationIssue> missing = v.issues().stream()
                .filter(i -> i.code().equals(PacketValidator.MISSING_SUBCATEGORY)).toList();
        assertThat(missing).hasSize(2);
        assertThat(missing).allSatisfy(i -> assertThat(i.severity()).isEqualTo(IssueSeverity.INFO));
        assertThat(missing.get(0).tossupId()).isEqualTo("t1");
        assertThat(missing.get(1).bonusId()).isEqualTo("b1");
    }

    @Test
    void playable_isTrueExactlyWithoutErrors() {
        // Warnings and infos only: playable.
        PacketValidation warningsOnly = validator.validate(packet(
                List.of(tossup("t1", null)),
                List.of(bonus("b1", 1, null, null), bonus("b2", 5, null, null))));
        assertThat(warningsOnly.issues()).isNotEmpty()
                .noneMatch(i -> i.severity() == IssueSeverity.ERROR);
        assertThat(warningsOnly.playable()).isTrue();

        // One ERROR among otherwise valid content: not playable.
        PacketValidation oneError = validator.validate(packet(
                List.of(tossup("t1", sub)),
                List.of(bonus("b1", 3, "P", sub), bonus("b2", 0, "P", sub))));
        assertThat(oneError.issues()).filteredOn(i -> i.severity() == IssueSeverity.ERROR).hasSize(1);
        assertThat(oneError.playable()).isFalse();
    }

    @Test
    void positionsFollowRelationshipOrder_notListOrder() {
        Packet p = packet(List.of(tossup("t1", sub)), List.of());
        Bonus first = bonus("first", 3, "P", sub);
        Bonus second = bonus("second", 2, "P", sub);
        // Stored out of order: "second" has order 1 but comes first in the list.
        p.setBonuses(new ArrayList<>(List.of(new ContainsBonus(1, second), new ContainsBonus(0, first))));
        ValidationIssue issue = only(validator.validate(p), PacketValidator.BONUS_PART_COUNT);
        assertThat(issue.message()).startsWith("Bonus 2 ");
    }

    @Test
    void messagesNeverQuoteQuestionOrAnswerText() {
        PacketValidation v = validator.validate(packet(
                List.of(tossup("t1", null)),
                List.of(bonus("b1", 2, "", null), bonus("b2", 0, "P", null))));
        assertThat(v.issues()).isNotEmpty().allSatisfy(i -> assertThat(i.message()).doesNotContain("secret"));
    }

    /* ------------------------------ write guards ----------------------------- */

    @Test
    void requireText_trims_andRejectsBlankAsBadRequest() {
        assertThat(validator.packetName("  Packet 1  ")).isEqualTo("Packet 1");
        assertThatThrownBy(() -> validator.packetName(" "))
                .isInstanceOf(InvalidApiRequestException.class);
        assertThatThrownBy(() -> validator.question(null, "Tossup question"))
                .isInstanceOf(InvalidApiRequestException.class);
    }

    @Test
    void lengthIsMeasuredAfterTrimming() {
        properties.getLimits().setAnswerMax(3);
        assertThat(validator.answer("  abc  ", "Tossup answer")).isEqualTo("abc");
        assertThatThrownBy(() -> validator.answer("abcd", "Tossup answer"))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo(PacketValidator.FIELD_ANSWER))
                .hasMessage("Tossup answer exceeds 3 characters");
    }

    @Test
    void preamble_isOptional() {
        assertThat(validator.preamble(null)).isNull();
        assertThat(validator.preamble("  ")).isEmpty();
        assertThatThrownBy(() -> validator.preamble("x".repeat(2001)))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void structuralCaps_followConfiguration() {
        properties.getLimits().setMaxTossups(2);
        properties.getLimits().setMaxBonuses(1);
        properties.getLimits().setMaxPartsPerBonus(4);
        assertThatCode(() -> validator.checkCanAddTossup(1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.checkCanAddTossup(2)).isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> validator.checkCanAddBonus(1)).isInstanceOf(ValidationFailedException.class);
        assertThatCode(() -> validator.checkNewBonusParts(4)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.checkNewBonusParts(5)).isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> validator.checkCanAddPart(4)).isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void minPartsFloor_neverDropsBelowOne() {
        properties.getLimits().setMinPartsPerBonus(0);
        assertThatThrownBy(() -> validator.checkNewBonusParts(0)).hasMessage("A bonus needs at least one part");
        assertThatThrownBy(() -> validator.checkCanRemovePart(1)).isInstanceOf(ValidationFailedException.class);
        assertThatCode(() -> validator.checkCanRemovePart(2)).doesNotThrowAnyException();
    }

    @Test
    void configuredMinParts_isEnforced() {
        properties.getLimits().setMinPartsPerBonus(2);
        assertThatThrownBy(() -> validator.checkNewBonusParts(1)).hasMessage("A bonus needs at least 2 parts");
        assertThatThrownBy(() -> validator.checkCanRemovePart(2)).isInstanceOf(ValidationFailedException.class);
    }
}

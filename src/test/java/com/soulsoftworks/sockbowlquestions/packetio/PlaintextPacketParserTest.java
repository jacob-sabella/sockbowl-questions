package com.soulsoftworks.sockbowlquestions.packetio;

import com.soulsoftworks.sockbowlquestions.exception.PayloadTooLargeException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PlaintextPacketParser} against plan 3.1.8's grammar: one test
 * per fixture (ACF-style headers/tags/power-marks/difficulty letters, NAQT-style with no
 * headers and a tiebreaker, CRLF+BOM, a malformed file) plus one per error/warning/info
 * code the parser itself emits (every code except {@code UNKNOWN_CATEGORY_TAG}, which is
 * only ever added later by {@code PacketImportService} once it has a database to resolve
 * tags against).
 */
class PlaintextPacketParserTest {

    private static final PacketLimits GENEROUS = new PacketLimits(
            200, 4000, 1000, 2000, 60, 60, 6, 1, 524_288);

    private final PlaintextPacketParser parser = new PlaintextPacketParser();

    /* ------------------------------------- fixtures ------------------------------------- */

    @Test
    void parsesAcfStyleFixtureWithHeadersTagsAndDifficultyLetters() {
        ParseResult result = parser.parse(fixture("acf-style.txt"), GENEROUS);

        assertThat(result.tossups()).hasSize(2);
        assertThat(result.bonuses()).hasSize(1);
        assertThat(result.issues()).noneMatch(ParseIssue::isError);

        ParsedTossup t1 = result.tossups().get(0);
        assertThat(t1.number()).isEqualTo(1);
        assertThat(t1.question()).contains("Paradise Lost").contains("(*)");
        assertThat(t1.answer()).isEqualTo("<b>John Milton</b> (accept either name)");
        assertThat(t1.categoryTag()).isEqualTo("ED, Literature - British Literature");

        ParsedTossup t2 = result.tossups().get(1);
        assertThat(t2.answer()).contains("carbon").contains("[accept carbon-12; prompt on \"C\"]");
        assertThat(t2.categoryTag()).isEqualTo("Science - Chemistry");

        ParsedBonus bonus = result.bonuses().get(0);
        assertThat(bonus.number()).isEqualTo(1);
        assertThat(bonus.preamble()).contains("For 10 points each");
        assertThat(bonus.parts()).hasSize(3);
        assertThat(bonus.parts().get(0).answer()).isEqualTo("Moby-Dick");
        assertThat(bonus.parts().get(1).answer()).isEqualTo("To Kill a Mockingbird");
        assertThat(bonus.parts().get(2).answer()).isEqualTo("Beloved");
        // The tag on the bonus's last part's ANSWER line is the bonus's own tag.
        assertThat(bonus.categoryTag()).isEqualTo("ED, Literature - American Literature");
        // A 10e/10h part value/difficulty isn't stored, but is noted with an INFO issue.
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.PART_VALUE_NOT_STORED));
    }

    @Test
    void parsesNaqtStyleFixtureWithNoHeadersAndATiebreaker() {
        ParseResult result = parser.parse(fixture("naqt-style.txt"), GENEROUS);

        // Two regular tossups plus the tiebreaker, inferred without any TOSSUPS header.
        assertThat(result.tossups()).hasSize(3);
        assertThat(result.tossups().get(0).answer()).isEqualTo("gravity");
        assertThat(result.tossups().get(1).categoryTag()).isEqualTo("Science: Biology");
        assertThat(result.tossups().get(2).answer()).isEqualTo("Paris");
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.TIEBREAKER_AS_TOSSUP));

        // The "1." bonus block is inferred purely from its [10] markers preceding ANSWER.
        assertThat(result.bonuses()).hasSize(1);
        assertThat(result.bonuses().get(0).parts()).hasSize(3);

        assertThat(result.suggestedName()).isEqualTo("Round 5");
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.PREFACE_IGNORED));
    }

    @Test
    void parsesCrlfAndBomFixture() {
        ParseResult result = parser.parse(fixture("crlf-bom.txt"), GENEROUS);

        assertThat(result.suggestedName()).isEqualTo("CRLF Packet");
        assertThat(result.tossups()).hasSize(1);
        assertThat(result.tossups().get(0).answer()).isEqualTo("crlf works");
        assertThat(result.bonuses()).hasSize(1);
        assertThat(result.bonuses().get(0).parts()).hasSize(3);
        assertThat(result.issues()).noneMatch(ParseIssue::isError);
    }

    @Test
    void malformedFixtureReportsEveryExpectedIssueAndDropsTheBadItems() {
        ParseResult result = parser.parse(fixture("malformed.txt"), GENEROUS);

        // Tossup 1 (no ANSWER) is dropped; tossup 2 and tossup 4 survive.
        assertThat(result.tossups()).extracting(ParsedTossup::number).containsExactly(2, 4);
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.MISSING_ANSWER) && i.line() == 5);
        // Numbering gap 2 -> 4.
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.NUMBERING_GAP));
        // The unresolved category tag is kept verbatim (resolution is the service's job).
        assertThat(result.tossups().get(0).categoryTag()).isEqualTo("Unknown Category - Nonexistent Subcat");
        assertThat(result.tossups().get(0).subcategory()).isNull();

        // Both bonuses are dropped: one for a part with no ANSWER, one for zero parts.
        assertThat(result.bonuses()).isEmpty();
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.PART_MISSING_ANSWER));
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.EMPTY_BONUS));
    }

    /* ------------------------------ per error/warning/info code ------------------------------- */

    @Test
    void missingQuestionIsAnError() {
        ParseResult result = parser.parse("""
                TOSSUPS

                1. ANSWER: nothing came before this
                """, GENEROUS);
        assertThat(result.tossups()).isEmpty();
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.MISSING_QUESTION) && i.severity() == IssueSeverity.ERROR);
    }

    @Test
    void tossupFieldTooLongIsAnError() {
        PacketLimits tinyQuestion = new PacketLimits(200, 10, 1000, 2000, 60, 60, 6, 1, 524_288);
        ParseResult result = parser.parse("""
                TOSSUPS

                1. This question text is much longer than ten characters.
                ANSWER: too long
                """, tinyQuestion);
        assertThat(result.tossups()).isEmpty();
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.FIELD_TOO_LONG));
    }

    @Test
    void tooManyTossupsDropsTheOverflowWithAnError() {
        PacketLimits capOne = new PacketLimits(200, 4000, 1000, 2000, 1, 60, 6, 1, 524_288);
        ParseResult result = parser.parse("""
                TOSSUPS

                1. First question here.
                ANSWER: first

                2. Second question here.
                ANSWER: second
                """, capOne);
        assertThat(result.tossups()).hasSize(1);
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.TOO_MANY_TOSSUPS));
    }

    @Test
    void tooManyBonusesDropsTheOverflowWithAnError() {
        PacketLimits capOne = new PacketLimits(200, 4000, 1000, 2000, 60, 1, 6, 1, 524_288);
        ParseResult result = parser.parse("""
                BONUSES

                1. First preamble.
                [10] part one
                ANSWER: one
                [10] part two
                ANSWER: two
                [10] part three
                ANSWER: three

                2. Second preamble.
                [10] part one
                ANSWER: one
                [10] part two
                ANSWER: two
                [10] part three
                ANSWER: three
                """, capOne);
        assertThat(result.bonuses()).hasSize(1);
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.TOO_MANY_BONUSES));
    }

    @Test
    void tooManyPartsIsAnError() {
        PacketLimits capParts = new PacketLimits(200, 4000, 1000, 2000, 60, 60, 2, 1, 524_288);
        ParseResult result = parser.parse("""
                BONUSES

                1. Preamble.
                [10] part one
                ANSWER: one
                [10] part two
                ANSWER: two
                [10] part three
                ANSWER: three
                """, capParts);
        assertThat(result.bonuses()).isEmpty();
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.TOO_MANY_PARTS));
    }

    @Test
    void duplicateTossupNumberIsAWarning() {
        ParseResult result = parser.parse("""
                TOSSUPS

                1. First question here.
                ANSWER: first

                1. Duplicated number question here.
                ANSWER: second
                """, GENEROUS);
        assertThat(result.tossups()).hasSize(2);
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.DUPLICATE_NUMBER) && i.severity() == IssueSeverity.WARNING);
    }

    @Test
    void unexpectedPartCountIsAWarning() {
        ParseResult result = parser.parse("""
                BONUSES

                1. Preamble.
                [10] only part
                ANSWER: one
                """, GENEROUS);
        assertThat(result.bonuses()).hasSize(1);
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.UNEXPECTED_PART_COUNT));
    }

    @Test
    void moreBonusesThanTossupsIsAWarning() {
        ParseResult result = parser.parse("""
                TOSSUPS

                1. Only tossup.
                ANSWER: one

                BONUSES

                1. First bonus.
                [10] a
                ANSWER: a
                [10] b
                ANSWER: b
                [10] c
                ANSWER: c

                2. Second bonus.
                [10] a
                ANSWER: a
                [10] b
                ANSWER: b
                [10] c
                ANSWER: c
                """, GENEROUS);
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.MORE_BONUSES_THAN_TOSSUPS));
    }

    @Test
    void emptyPreambleIsAWarning() {
        ParseResult result = parser.parse("""
                BONUSES

                1.
                [10] a
                ANSWER: a
                [10] b
                ANSWER: b
                [10] c
                ANSWER: c
                """, GENEROUS);
        assertThat(result.bonuses()).hasSize(1);
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.EMPTY_PREAMBLE));
    }

    @Test
    void prefaceIgnoredIsInfoAndSetsSuggestedName() {
        ParseResult result = parser.parse("""
                My Great Packet

                TOSSUPS

                1. First question here.
                ANSWER: first
                """, GENEROUS);
        assertThat(result.suggestedName()).isEqualTo("My Great Packet");
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.PREFACE_IGNORED) && i.severity() == IssueSeverity.INFO);
    }

    @Test
    void tiebreakerAsTossupIsInfo() {
        ParseResult result = parser.parse("""
                TOSSUPS

                TB. A tiebreaker question.
                ANSWER: tb answer
                """, GENEROUS);
        assertThat(result.tossups()).hasSize(1);
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.TIEBREAKER_AS_TOSSUP));
    }

    @Test
    void partValueNotStoredIsInfoForNonDefaultValues() {
        ParseResult result = parser.parse("""
                BONUSES

                1. Preamble.
                [15] a harder part
                ANSWER: a
                [10] a normal part
                ANSWER: b
                [10] another normal part
                ANSWER: c
                """, GENEROUS);
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.PART_VALUE_NOT_STORED));
    }

    @Test
    void partMissingAnswerDropsTheWholeBonus() {
        ParseResult result = parser.parse("""
                BONUSES

                1. Preamble.
                [10] a part with no answer at all
                [10] b
                ANSWER: b
                [10] c
                ANSWER: c
                """, GENEROUS);
        assertThat(result.bonuses()).isEmpty();
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.PART_MISSING_ANSWER));
    }

    @Test
    void emptyBonusWithNoPartsIsAnError() {
        ParseResult result = parser.parse("""
                BONUSES

                1. Just a preamble and nothing else.
                """, GENEROUS);
        assertThat(result.bonuses()).isEmpty();
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.EMPTY_BONUS));
    }

    @Test
    void oversizeInputIsRejectedBeforeParsing() {
        PacketLimits tinyByteLimit = new PacketLimits(200, 4000, 1000, 2000, 60, 60, 6, 1, 10);
        String tooLong = "TOSSUPS\n\n1. way more than ten bytes of text.\nANSWER: yes\n";
        assertThatThrownBy(() -> parser.parse(tooLong, tinyByteLimit))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void categoryOnlyTagIsSplitWithNullSubcategory() {
        ParseResult result = parser.parse("""
                TOSSUPS

                1. A question with only a category tag, no subcategory.
                ANSWER: answer <Science>
                """, GENEROUS);
        assertThat(result.tossups().get(0).categoryTag()).isEqualTo("Science");
    }

    /* ------------------------------------------ helpers --------------------------------------- */

    private static String fixture(String name) {
        try (InputStream in = PlaintextPacketParserTest.class.getResourceAsStream("/packets/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

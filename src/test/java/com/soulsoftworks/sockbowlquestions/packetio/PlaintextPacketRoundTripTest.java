package com.soulsoftworks.sockbowlquestions.packetio;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip property (plan 3.1.8): {@code parse(format(p))} reproduces the same name,
 * tossups (question, answer, subcategory), bonuses (preamble, subcategory) and parts
 * (question, answer, order) — the only deliberate loss is part values/difficulty
 * letters, which the format doesn't carry.
 */
class PlaintextPacketRoundTripTest {

    private static final PacketLimits GENEROUS = new PacketLimits(
            200, 4000, 1000, 2000, 60, 60, 6, 1, 524_288);

    private final PlaintextPacketParser parser = new PlaintextPacketParser();
    private final PlaintextPacketFormatter formatter = new PlaintextPacketFormatter();

    @Test
    void roundTripsAPacketWithoutSubcategoriesAndThreePartBonuses() {
        Packet packet = packetWithTossupsAndBonuses("No-Subcat Packet", false);
        assertRoundTrips(packet);
    }

    @Test
    void roundTripsAPacketWithSubcategoriesAndAOneAndSixPartBonus() {
        Packet packet = Packet.builder().name("Subcat Packet").build();
        Category science = Category.builder().id("cat-sci").name("Science").build();
        Subcategory bio = Subcategory.builder().id("sub-bio").name("Biology").category(science).build();

        Tossup t1 = Tossup.builder().id("t1").question("Question one?").answer("Answer one").subcategory(bio).build();
        Tossup t2 = Tossup.builder().id("t2").question("Question two?").answer("Answer two").build();
        packet.setTossups(List.of(
                ContainsTossup.builder().order(0).tossup(t1).build(),
                ContainsTossup.builder().order(1).tossup(t2).build()));

        Bonus onePart = Bonus.builder().id("b1").preamble("One-part preamble.").subcategory(bio)
                .bonusParts(List.of(new HasBonusPart(0, BonusPart.builder().id("p1").question("Only part?").answer("Only answer").build())))
                .build();
        Bonus sixPart = Bonus.builder().id("b2").preamble("Six-part preamble.")
                .bonusParts(sixParts()).build();
        packet.setBonuses(List.of(
                new ContainsBonus(0, onePart),
                new ContainsBonus(1, sixPart)));

        assertRoundTrips(packet);
    }

    @Test
    void roundTripsUnicodePowerMarksAndBrackets() {
        Packet packet = Packet.builder().name("Unicode Packet — épreuve").build();
        Tossup t1 = Tossup.builder().id("t1")
                .question("This (*) tossup mentions café, ümläuts, and [brackets] plus <b>markup</b>.")
                .answer("réponse [accept café]").build();
        packet.setTossups(List.of(ContainsTossup.builder().order(0).tossup(t1).build()));
        packet.setBonuses(List.of());

        assertRoundTrips(packet);
    }

    /** The ACF fixture round-trips from the other direction: format(toPacket(parse(fixture))) reparses identically. */
    @Test
    void acfFixtureRoundTripsFromParsedFormBackThroughTheFormatter() {
        String fixtureText = fixture("acf-style.txt");
        ParseResult firstParse = parser.parse(fixtureText, GENEROUS);
        assertThat(firstParse.issues()).noneMatch(ParseIssue::isError);

        Packet rebuilt = toPacket(firstParse.suggestedName() == null ? "ACF" : firstParse.suggestedName(),
                firstParse.tossups(), firstParse.bonuses());
        String reformatted = formatter.format(rebuilt);
        ParseResult secondParse = parser.parse(reformatted, GENEROUS);

        assertThat(secondParse.tossups()).hasSize(firstParse.tossups().size());
        assertThat(secondParse.bonuses()).hasSize(firstParse.bonuses().size());
        for (int i = 0; i < firstParse.tossups().size(); i++) {
            assertThat(secondParse.tossups().get(i).question()).isEqualTo(firstParse.tossups().get(i).question());
            assertThat(secondParse.tossups().get(i).answer()).isEqualTo(firstParse.tossups().get(i).answer());
        }
        for (int i = 0; i < firstParse.bonuses().size(); i++) {
            assertThat(secondParse.bonuses().get(i).preamble()).isEqualTo(firstParse.bonuses().get(i).preamble());
            assertThat(secondParse.bonuses().get(i).parts()).hasSize(firstParse.bonuses().get(i).parts().size());
        }
    }

    /* --------------------------------------- helpers ------------------------------------------ */

    private void assertRoundTrips(Packet original) {
        String text = formatter.format(original);
        ParseResult parsed = parser.parse(text, GENEROUS);
        assertThat(parsed.issues()).filteredOn(ParseIssue::isError).isEmpty();
        // The only warnings a well-formed input like this may still legitimately produce
        // are D7 "not exactly 3 parts" ones (this test deliberately covers 1- and 6-part
        // bonuses); everything else would mean the round trip lost or corrupted data.
        assertThat(parsed.issues())
                .filteredOn(i -> i.severity() == IssueSeverity.WARNING)
                .allMatch(i -> i.code().equals(ParseIssue.UNEXPECTED_PART_COUNT));

        assertThat(parsed.suggestedName()).isEqualTo(original.getName());

        List<ContainsTossup> originalTossups = original.getTossups() == null ? List.of() : original.getTossups();
        assertThat(parsed.tossups()).hasSize(originalTossups.size());
        for (int i = 0; i < originalTossups.size(); i++) {
            Tossup expected = originalTossups.get(i).getTossup();
            ParsedTossup actual = parsed.tossups().get(i);
            assertThat(actual.question()).isEqualTo(expected.getQuestion());
            assertThat(actual.answer()).isEqualTo(expected.getAnswer());
            assertSameSubcategoryTag(actual.categoryTag(), expected.getSubcategory());
        }

        List<ContainsBonus> originalBonuses = original.getBonuses() == null ? List.of() : original.getBonuses();
        assertThat(parsed.bonuses()).hasSize(originalBonuses.size());
        for (int i = 0; i < originalBonuses.size(); i++) {
            Bonus expected = originalBonuses.get(i).getBonus();
            ParsedBonus actual = parsed.bonuses().get(i);
            assertThat(actual.preamble()).isEqualTo(expected.getPreamble());
            assertSameSubcategoryTag(actual.categoryTag(), expected.getSubcategory());
            assertThat(actual.parts()).hasSize(expected.getBonusParts().size());
            for (int j = 0; j < actual.parts().size(); j++) {
                BonusPart expectedPart = expected.getBonusParts().get(j).getBonusPart();
                ParsedBonusPart actualPart = actual.parts().get(j);
                assertThat(actualPart.question()).isEqualTo(expectedPart.getQuestion());
                assertThat(actualPart.answer()).isEqualTo(expectedPart.getAnswer());
            }
        }
    }

    /**
     * The round trip's notion of "the same subcategory" (plan 3.1.8's "subcategory id"):
     * the bare parser never resolves a name back into a database id (it has no database
     * to resolve against — that's {@code PacketImportService}'s job, covered by
     * {@code PacketImportCypherIT}). What the format/parse pair must preserve exactly is
     * the category and subcategory NAME the formatter wrote, so this compares the raw tag
     * the parser captured against the original subcategory's (and its category's) name.
     */
    private static void assertSameSubcategoryTag(String categoryTag, Subcategory expected) {
        if (expected == null) {
            assertThat(categoryTag).isNull();
            return;
        }
        CategoryTag.TagParts parts = CategoryTag.split(categoryTag);
        assertThat(parts).as("category tag on a tossup/bonus with a subcategory").isNotNull();
        assertThat(parts.subcategory()).isEqualTo(expected.getName());
        if (expected.getCategory() != null) {
            assertThat(parts.category()).isEqualTo(expected.getCategory().getName());
        }
    }

    private Packet packetWithTossupsAndBonuses(String name, boolean unused) {
        Packet packet = Packet.builder().name(name).build();
        Tossup t1 = Tossup.builder().id("t1").question("First question?").answer("First answer").build();
        Tossup t2 = Tossup.builder().id("t2").question("Second question, with a comma?").answer("Second answer").build();
        packet.setTossups(List.of(
                ContainsTossup.builder().order(0).tossup(t1).build(),
                ContainsTossup.builder().order(1).tossup(t2).build()));

        Bonus bonus = Bonus.builder().id("b1").preamble("A preamble for ten points each.")
                .bonusParts(List.of(
                        new HasBonusPart(0, BonusPart.builder().id("p1").question("Part one?").answer("Part one answer").build()),
                        new HasBonusPart(1, BonusPart.builder().id("p2").question("Part two?").answer("Part two answer").build()),
                        new HasBonusPart(2, BonusPart.builder().id("p3").question("Part three?").answer("Part three answer").build())))
                .build();
        packet.setBonuses(List.of(new ContainsBonus(0, bonus)));
        return packet;
    }

    private List<HasBonusPart> sixParts() {
        List<HasBonusPart> parts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            parts.add(new HasBonusPart(i, BonusPart.builder().id("six-" + i)
                    .question("Part " + i + " question?").answer("Part " + i + " answer").build()));
        }
        return parts;
    }

    private static Packet toPacket(String name, List<ParsedTossup> tossups, List<ParsedBonus> bonuses) {
        Packet packet = Packet.builder().name(name).build();
        List<ContainsTossup> tossupRels = new ArrayList<>();
        int order = 0;
        for (ParsedTossup t : tossups) {
            Tossup tossup = Tossup.builder().id("t" + order).question(t.question()).answer(t.answer()).build();
            tossupRels.add(ContainsTossup.builder().order(order).tossup(tossup).build());
            order++;
        }
        packet.setTossups(tossupRels);

        List<ContainsBonus> bonusRels = new ArrayList<>();
        order = 0;
        for (ParsedBonus b : bonuses) {
            List<HasBonusPart> parts = new ArrayList<>();
            int partOrder = 0;
            for (ParsedBonusPart p : b.parts()) {
                parts.add(new HasBonusPart(partOrder, BonusPart.builder().id("p" + order + "-" + partOrder)
                        .question(p.question()).answer(p.answer()).build()));
                partOrder++;
            }
            Bonus bonus = Bonus.builder().id("b" + order).preamble(b.preamble()).bonusParts(parts).build();
            bonusRels.add(new ContainsBonus(order, bonus));
            order++;
        }
        packet.setBonuses(bonusRels);
        return packet;
    }

    private static String fixture(String name) {
        try (InputStream in = PlaintextPacketRoundTripTest.class.getResourceAsStream("/packets/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

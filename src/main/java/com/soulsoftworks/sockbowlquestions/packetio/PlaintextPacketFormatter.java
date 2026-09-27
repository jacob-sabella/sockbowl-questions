package com.soulsoftworks.sockbowlquestions.packetio;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;

import java.util.Comparator;
import java.util.List;

/**
 * Writes a {@link Packet} back to the canonical plaintext form {@link PlaintextPacketParser}
 * reads (plan 3.1.8): {@code parse(format(p))} reproduces the same name, tossups (question,
 * answer, subcategory), bonuses (preamble, subcategory) and parts (question, answer, order) —
 * the only deliberate loss is part values/difficulty letters, which the grammar doesn't model.
 */
public class PlaintextPacketFormatter {

    public String format(Packet packet) {
        StringBuilder sb = new StringBuilder();
        String name = packet.getName();
        sb.append(name == null ? "" : name).append("\n\n");

        List<ContainsTossup> tossups = packet.getTossups() == null ? List.of()
                : packet.getTossups().stream()
                        .sorted(Comparator.comparing(r -> orderOf(r.getOrder())))
                        .toList();
        List<ContainsBonus> bonuses = packet.getBonuses() == null ? List.of()
                : packet.getBonuses().stream()
                        .sorted(Comparator.comparing(r -> orderOf(r.getOrder())))
                        .toList();

        sb.append("TOSSUPS\n\n");
        int number = 1;
        for (ContainsTossup rel : tossups) {
            Tossup t = rel.getTossup();
            if (t == null) {
                continue;
            }
            sb.append(number++).append(". ").append(nullToEmpty(t.getQuestion())).append('\n');
            sb.append("ANSWER: ").append(nullToEmpty(t.getAnswer()));
            appendTag(sb, t.getSubcategory());
            sb.append("\n\n");
        }

        sb.append("BONUSES\n\n");
        number = 1;
        for (ContainsBonus rel : bonuses) {
            Bonus b = rel.getBonus();
            if (b == null) {
                continue;
            }
            sb.append(number++).append(". ").append(nullToEmpty(b.getPreamble())).append('\n');
            List<HasBonusPart> parts = b.getBonusParts() == null ? List.of()
                    : b.getBonusParts().stream()
                            .sorted(Comparator.comparing(r -> orderOf(r.getOrder())))
                            .toList();
            for (int i = 0; i < parts.size(); i++) {
                var part = parts.get(i).getBonusPart();
                if (part == null) {
                    continue;
                }
                sb.append("[10] ").append(nullToEmpty(part.getQuestion())).append('\n');
                sb.append("ANSWER: ").append(nullToEmpty(part.getAnswer()));
                if (i == parts.size() - 1) {
                    appendTag(sb, b.getSubcategory());
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        return sb.toString().stripTrailing() + "\n";
    }

    private static void appendTag(StringBuilder sb, Subcategory subcategory) {
        if (subcategory == null || subcategory.getName() == null) {
            return;
        }
        String categoryName = subcategory.getCategory() != null ? subcategory.getCategory().getName() : null;
        sb.append(" <");
        if (categoryName != null && !categoryName.isBlank()) {
            sb.append(categoryName).append(" - ");
        }
        sb.append(subcategory.getName()).append('>');
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static int orderOf(Integer order) {
        return order == null ? Integer.MAX_VALUE : order;
    }
}

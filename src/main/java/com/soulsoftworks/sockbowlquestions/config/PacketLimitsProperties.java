package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Authoring limits for packets, plaintext imports and taxonomy names (M3, plan 3.1.1),
 * bound from {@code sockbowl.packet.*}. Env overrides use Spring's relaxed binding,
 * for example {@code SOCKBOWL_PACKET_LIMITS_NAME_MAX} or
 * {@code SOCKBOWL_PACKET_IMPORT_MAX_BYTES}.
 *
 * <p>The same prefix also carries the EPHEMERAL-packet settings bound by
 * {@link EphemeralPacketProperties}; the two classes bind disjoint keys.
 *
 * <p>Plain getters and setters rather than Lombok, because the {@code import} group
 * needs a {@code getImport()/setImport()} pair ({@code import} is a Java keyword, so it
 * can't be a field name).
 */
@Configuration
@ConfigurationProperties(prefix = "sockbowl.packet")
public class PacketLimitsProperties {

    private Limits limits = new Limits();
    private Import importSettings = new Import();
    private Taxonomy taxonomy = new Taxonomy();

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    /** {@code sockbowl.packet.import.*}. */
    public Import getImport() {
        return importSettings;
    }

    public void setImport(Import importSettings) {
        this.importSettings = importSettings;
    }

    public Taxonomy getTaxonomy() {
        return taxonomy;
    }

    public void setTaxonomy(Taxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    /** {@code sockbowl.packet.limits.*}: field lengths and structural caps. */
    public static class Limits {
        /** Max characters in a packet name. */
        private int nameMax = 200;
        /** Max characters in a tossup question or a bonus part question. */
        private int questionMax = 4000;
        /** Max characters in a tossup answer or a bonus part answer. */
        private int answerMax = 1000;
        /** Max characters in a bonus preamble. */
        private int preambleMax = 2000;
        /** Max tossups per packet. */
        private int maxTossups = 60;
        /** Max bonuses per packet. */
        private int maxBonuses = 60;
        /** Max parts per bonus. */
        private int maxPartsPerBonus = 6;
        /** Hard floor on parts per bonus. 3 is the D7 "expected" count, a warning only. */
        private int minPartsPerBonus = 1;

        public int getNameMax() {
            return nameMax;
        }

        public void setNameMax(int nameMax) {
            this.nameMax = nameMax;
        }

        public int getQuestionMax() {
            return questionMax;
        }

        public void setQuestionMax(int questionMax) {
            this.questionMax = questionMax;
        }

        public int getAnswerMax() {
            return answerMax;
        }

        public void setAnswerMax(int answerMax) {
            this.answerMax = answerMax;
        }

        public int getPreambleMax() {
            return preambleMax;
        }

        public void setPreambleMax(int preambleMax) {
            this.preambleMax = preambleMax;
        }

        public int getMaxTossups() {
            return maxTossups;
        }

        public void setMaxTossups(int maxTossups) {
            this.maxTossups = maxTossups;
        }

        public int getMaxBonuses() {
            return maxBonuses;
        }

        public void setMaxBonuses(int maxBonuses) {
            this.maxBonuses = maxBonuses;
        }

        public int getMaxPartsPerBonus() {
            return maxPartsPerBonus;
        }

        public void setMaxPartsPerBonus(int maxPartsPerBonus) {
            this.maxPartsPerBonus = maxPartsPerBonus;
        }

        public int getMinPartsPerBonus() {
            return minPartsPerBonus;
        }

        public void setMinPartsPerBonus(int minPartsPerBonus) {
            this.minPartsPerBonus = minPartsPerBonus;
        }
    }

    /** {@code sockbowl.packet.import.*}: plaintext import (D5). */
    public static class Import {
        /** Max size of a plaintext import, in UTF-8 bytes. Default 512 KiB. */
        private long maxBytes = 524_288;

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    /** {@code sockbowl.packet.taxonomy.*}: category, subcategory and difficulty names (D4). */
    public static class Taxonomy {
        /** Max characters in a category, subcategory or difficulty name. */
        private int nameMax = 100;
        /**
         * Merge case-insensitive duplicate taxonomy entries at startup before creating the
         * uniqueness constraints. Off by default: the admin merge action is the supported
         * path, and nothing destructive runs without the operator opting in.
         */
        private boolean dedupeOnStartup = false;

        public int getNameMax() {
            return nameMax;
        }

        public void setNameMax(int nameMax) {
            this.nameMax = nameMax;
        }

        public boolean isDedupeOnStartup() {
            return dedupeOnStartup;
        }

        public void setDedupeOnStartup(boolean dedupeOnStartup) {
            this.dedupeOnStartup = dedupeOnStartup;
        }
    }
}

package com.soulsoftworks.sockbowlquestions.packetio;

import com.soulsoftworks.sockbowlquestions.exception.PayloadTooLargeException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a plaintext quiz-bowl packet (D5, plan 3.1.8) into structured tossups and
 * bonuses, never touching the database: taxonomy tags are kept as raw text
 * ({@link ParsedTossup#categoryTag()}/{@link ParsedBonus#categoryTag()}) for
 * {@code PacketImportService} to resolve afterwards. Pure Java, no Spring/Neo4j
 * dependency, so it is fast to unit test directly.
 *
 * <p>Algorithm: normalize the text, then split it into line-bounded "blocks" (one per
 * item, from an item-start or tiebreaker line up to the next one), then parse each
 * block according to its kind (tossup or bonus), resolved from an explicit
 * {@code TOSSUP}/{@code BONUS} keyword, the most recent section header, or — with
 * neither — inferred from whether a bonus-part marker appears before the first
 * {@code ANSWER:} line.
 */
public class PlaintextPacketParser {

    private static final Pattern HEADER =
            Pattern.compile("^\\s*(TOSSUPS?|BONUSES?)\\s*:?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_START =
            Pattern.compile("^\\s*(?:(TOSSUP|BONUS)\\s+)?(\\d{1,3})[.)]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIEBREAKER =
            Pattern.compile("^\\s*(?:TB|TIEBREAKER|EXTRA)\\s*(\\d*)[.):]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER =
            Pattern.compile("^\\s*ANSWER\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART =
            Pattern.compile("^\\s*\\[\\s*(\\d{1,2})\\s*([EMHemh])?\\s*]\\s*(.*)$");
    private static final Pattern TAG_TRAILING = Pattern.compile("<([^<>]*)>\\s*$");
    private static final Pattern TAG_ONLY_LINE = Pattern.compile("^\\s*<([^<>]*)>\\s*$");

    private static final int EXPECTED_PARTS_PER_BONUS = 3;

    public ParseResult parse(String text, PacketLimits limits) {
        if (text == null) {
            text = "";
        }
        long byteLength = text.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > limits.importMaxBytes()) {
            throw new PayloadTooLargeException(limits.importMaxBytes(),
                    "Import text (" + byteLength + " bytes) exceeds the limit of "
                            + limits.importMaxBytes() + " bytes");
        }

        String normalized = normalize(text);
        String[] rawLines = normalized.split("\n", -1);

        List<ParseIssue> issues = new ArrayList<>();
        List<RawLine> preface = new ArrayList<>();
        List<RawBlock> blocks = new ArrayList<>();
        RawBlock current = null;
        Mode headerMode = null;

        for (int i = 0; i < rawLines.length; i++) {
            int lineNo = i + 1;
            String line = rawLines[i];

            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                headerMode = header.group(1).toUpperCase(Locale.ROOT).startsWith("TOSSUP") ? Mode.TOSSUPS : Mode.BONUSES;
                continue;
            }

            Matcher itemStart = ITEM_START.matcher(line);
            boolean isItemStart = itemStart.matches();
            Matcher tie = isItemStart ? null : TIEBREAKER.matcher(line);
            boolean isTiebreaker = tie != null && tie.matches();

            if (isItemStart || isTiebreaker) {
                if (current != null) {
                    blocks.add(current);
                }
                String kindHint;
                Integer number;
                String remainder;
                if (isItemStart) {
                    kindHint = itemStart.group(1);
                    number = Integer.parseInt(itemStart.group(2));
                    remainder = itemStart.group(3);
                } else {
                    kindHint = null;
                    String numGroup = tie.group(1);
                    number = (numGroup == null || numGroup.isBlank()) ? null : Integer.parseInt(numGroup);
                    remainder = tie.group(2);
                }
                current = new RawBlock(lineNo, kindHint, number, isTiebreaker, headerMode);
                if (remainder != null && !remainder.isBlank()) {
                    current.lines.add(new RawLine(lineNo, remainder));
                }
                continue;
            }

            if (current == null) {
                if (!line.isBlank()) {
                    preface.add(new RawLine(lineNo, line));
                }
                continue;
            }
            current.lines.add(new RawLine(lineNo, line));
        }
        if (current != null) {
            blocks.add(current);
        }

        String suggestedName = null;
        for (RawLine p : preface) {
            String trimmed = p.text().trim();
            if (suggestedName == null) {
                suggestedName = trimmed;
            }
            issues.add(ParseIssue.info(ParseIssue.PREFACE_IGNORED, p.lineNo(),
                    "Line ignored before the first item: \"" + trimmed + "\""));
        }

        List<ParsedTossup> tossups = new ArrayList<>();
        List<ParsedBonus> bonuses = new ArrayList<>();

        for (RawBlock block : blocks) {
            if (block.tiebreaker) {
                issues.add(ParseIssue.info(ParseIssue.TIEBREAKER_AS_TOSSUP, block.startLine,
                        "Tiebreaker at line " + block.startLine + " imported as a regular tossup"));
            }
            Kind kind = resolveKind(block);
            if (kind == Kind.TOSSUP) {
                if (tossups.size() >= limits.maxTossups()) {
                    issues.add(ParseIssue.error(ParseIssue.TOO_MANY_TOSSUPS, block.startLine,
                            "Packet exceeds the limit of " + limits.maxTossups()
                                    + " tossups; the tossup at line " + block.startLine + " was dropped"));
                    continue;
                }
                parseTossupBlock(block, limits, issues).ifPresent(tossups::add);
            } else {
                if (bonuses.size() >= limits.maxBonuses()) {
                    issues.add(ParseIssue.error(ParseIssue.TOO_MANY_BONUSES, block.startLine,
                            "Packet exceeds the limit of " + limits.maxBonuses()
                                    + " bonuses; the bonus at line " + block.startLine + " was dropped"));
                    continue;
                }
                parseBonusBlock(block, limits, issues).ifPresent(bonuses::add);
            }
        }

        checkNumbering(tossups.stream().map(ParsedTossup::number).toList(), "Tossup", issues);
        checkNumbering(bonuses.stream().map(ParsedBonus::number).toList(), "Bonus", issues);

        if (!bonuses.isEmpty() && bonuses.size() > tossups.size()) {
            issues.add(ParseIssue.warning(ParseIssue.MORE_BONUSES_THAN_TOSSUPS, null,
                    "The packet has " + bonuses.size() + " bonuses but only " + tossups.size()
                            + " tossups; bonuses past tossup " + tossups.size() + " are never read"));
        }

        return new ParseResult(suggestedName, tossups, bonuses, issues);
    }

    /* ------------------------------------- block kind -------------------------------------- */

    private enum Mode { TOSSUPS, BONUSES }

    private enum Kind { TOSSUP, BONUS }

    private static Kind resolveKind(RawBlock block) {
        if (block.tiebreaker) {
            return Kind.TOSSUP;
        }
        if ("TOSSUP".equalsIgnoreCase(block.kindHint)) {
            return Kind.TOSSUP;
        }
        if ("BONUS".equalsIgnoreCase(block.kindHint)) {
            return Kind.BONUS;
        }
        if (block.headerMode == Mode.TOSSUPS) {
            return Kind.TOSSUP;
        }
        if (block.headerMode == Mode.BONUSES) {
            return Kind.BONUS;
        }
        int answerIdx = indexOfFirstMatch(block.lines, ANSWER);
        int partIdx = indexOfFirstMatch(block.lines, PART);
        if (partIdx >= 0 && (answerIdx < 0 || partIdx < answerIdx)) {
            return Kind.BONUS;
        }
        return Kind.TOSSUP;
    }

    /* ------------------------------------ tossup block -------------------------------------- */

    private java.util.Optional<ParsedTossup> parseTossupBlock(RawBlock block, PacketLimits limits, List<ParseIssue> issues) {
        List<RawLine> lines = block.lines;
        int answerIdx = indexOfFirstMatch(lines, ANSWER);
        if (answerIdx < 0) {
            issues.add(ParseIssue.error(ParseIssue.MISSING_ANSWER, block.startLine,
                    "The tossup at line " + block.startLine + " has no ANSWER line"));
            return java.util.Optional.empty();
        }
        String question = joinText(lines.subList(0, answerIdx));
        if (question.isBlank()) {
            issues.add(ParseIssue.error(ParseIssue.MISSING_QUESTION, lines.get(answerIdx).lineNo(),
                    "The tossup at line " + block.startLine + " has no question text"));
            return java.util.Optional.empty();
        }

        TagCapture capture = captureAnswerAndTag(lines, answerIdx, ANSWER);
        String answer = capture.text().trim();
        if (answer.isBlank()) {
            issues.add(ParseIssue.error(ParseIssue.MISSING_ANSWER, lines.get(answerIdx).lineNo(),
                    "The tossup at line " + block.startLine + " has an empty answer"));
            return java.util.Optional.empty();
        }
        if (question.length() > limits.questionMax()) {
            issues.add(ParseIssue.error(ParseIssue.FIELD_TOO_LONG, block.startLine,
                    "The tossup at line " + block.startLine + " question exceeds " + limits.questionMax() + " characters"));
            return java.util.Optional.empty();
        }
        if (answer.length() > limits.answerMax()) {
            issues.add(ParseIssue.error(ParseIssue.FIELD_TOO_LONG, lines.get(answerIdx).lineNo(),
                    "The tossup at line " + block.startLine + " answer exceeds " + limits.answerMax() + " characters"));
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ParsedTossup(block.number, block.startLine, question, answer, capture.tag(), null));
    }

    /* ------------------------------------- bonus block --------------------------------------- */

    private java.util.Optional<ParsedBonus> parseBonusBlock(RawBlock block, PacketLimits limits, List<ParseIssue> issues) {
        List<RawLine> lines = block.lines;
        List<Integer> markerIdx = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (PART.matcher(lines.get(i).text()).matches()) {
                markerIdx.add(i);
            }
        }
        if (markerIdx.isEmpty()) {
            issues.add(ParseIssue.error(ParseIssue.EMPTY_BONUS, block.startLine,
                    "The bonus at line " + block.startLine + " has no parts"));
            return java.util.Optional.empty();
        }

        String preamble = joinText(lines.subList(0, markerIdx.get(0)));
        boolean emptyPreamble = preamble.isBlank();
        if (preamble.length() > limits.preambleMax()) {
            issues.add(ParseIssue.error(ParseIssue.FIELD_TOO_LONG, block.startLine,
                    "The bonus at line " + block.startLine + " preamble exceeds " + limits.preambleMax() + " characters"));
            return java.util.Optional.empty();
        }

        List<ParsedBonusPart> parts = new ArrayList<>();
        String categoryTag = null;
        boolean dropped = false;

        for (int k = 0; k < markerIdx.size(); k++) {
            int start = markerIdx.get(k);
            int end = (k + 1 < markerIdx.size()) ? markerIdx.get(k + 1) : lines.size();
            List<RawLine> region = lines.subList(start, end);

            Matcher pm = PART.matcher(region.get(0).text());
            pm.matches();
            String valueStr = pm.group(1);
            String diffLetter = pm.group(2);
            String remainder = pm.group(3);

            List<RawLine> regionLines = new ArrayList<>();
            regionLines.add(new RawLine(region.get(0).lineNo(), remainder));
            regionLines.addAll(region.subList(1, region.size()));

            int ansIdx = indexOfFirstMatch(regionLines, ANSWER);
            int markerLine = region.get(0).lineNo();
            if (ansIdx < 0) {
                issues.add(ParseIssue.error(ParseIssue.PART_MISSING_ANSWER, markerLine,
                        "The bonus part at line " + markerLine + " has no ANSWER line"));
                dropped = true;
                continue;
            }
            String partQuestion = joinText(regionLines.subList(0, ansIdx));
            if (partQuestion.isBlank()) {
                issues.add(ParseIssue.error(ParseIssue.PART_MISSING_ANSWER, markerLine,
                        "The bonus part at line " + markerLine + " has no question text"));
                dropped = true;
                continue;
            }
            TagCapture capture = captureAnswerAndTag(regionLines, ansIdx, ANSWER);
            String partAnswer = capture.text().trim();
            if (capture.tag() != null) {
                categoryTag = capture.tag();
            }
            if (partAnswer.isBlank()) {
                issues.add(ParseIssue.error(ParseIssue.PART_MISSING_ANSWER, markerLine,
                        "The bonus part at line " + markerLine + " has an empty answer"));
                dropped = true;
                continue;
            }
            if (partQuestion.length() > limits.questionMax() || partAnswer.length() > limits.answerMax()) {
                issues.add(ParseIssue.error(ParseIssue.FIELD_TOO_LONG, markerLine,
                        "The bonus part at line " + markerLine + " exceeds a field length limit"));
                dropped = true;
                continue;
            }
            if (!"10".equals(valueStr) || diffLetter != null) {
                issues.add(ParseIssue.info(ParseIssue.PART_VALUE_NOT_STORED, markerLine,
                        "Part value/difficulty at line " + markerLine + " is not stored"));
            }
            parts.add(new ParsedBonusPart(markerLine, partQuestion, partAnswer));
        }

        if (dropped) {
            // One bad part invalidates the whole bonus; the specific ERROR(s) above explain why.
            return java.util.Optional.empty();
        }
        if (parts.isEmpty()) {
            issues.add(ParseIssue.error(ParseIssue.EMPTY_BONUS, block.startLine,
                    "The bonus at line " + block.startLine + " has no parts"));
            return java.util.Optional.empty();
        }
        if (parts.size() > limits.maxPartsPerBonus()) {
            issues.add(ParseIssue.error(ParseIssue.TOO_MANY_PARTS, block.startLine,
                    "The bonus at line " + block.startLine + " has more than " + limits.maxPartsPerBonus() + " parts"));
            return java.util.Optional.empty();
        }
        if (emptyPreamble) {
            issues.add(ParseIssue.warning(ParseIssue.EMPTY_PREAMBLE, block.startLine,
                    "The bonus at line " + block.startLine + " has an empty preamble"));
        }
        if (parts.size() != EXPECTED_PARTS_PER_BONUS) {
            issues.add(ParseIssue.warning(ParseIssue.UNEXPECTED_PART_COUNT, block.startLine,
                    "The bonus at line " + block.startLine + " has " + parts.size()
                            + " part" + (parts.size() == 1 ? "" : "s") + "; " + EXPECTED_PARTS_PER_BONUS + " is standard"));
        }
        return java.util.Optional.of(new ParsedBonus(block.number, block.startLine, preamble, List.copyOf(parts), categoryTag, null));
    }

    /* ------------------------------------- shared helpers ------------------------------------- */

    /** The answer text (or part answer text) plus a trailing/following category tag, if any. */
    private record TagCapture(String text, String tag) {}

    private static TagCapture captureAnswerAndTag(List<RawLine> lines, int markerLineIdx, Pattern markerPattern) {
        Matcher m = markerPattern.matcher(lines.get(markerLineIdx).text());
        m.matches();
        String firstPart = m.group(1);
        String tag = null;

        Matcher trailing = TAG_TRAILING.matcher(firstPart);
        if (trailing.find()) {
            tag = trailing.group(1).trim();
            firstPart = firstPart.substring(0, trailing.start()).trim();
        }
        StringBuilder buf = new StringBuilder(firstPart);

        for (int i = markerLineIdx + 1; i < lines.size(); i++) {
            String text = lines.get(i).text();
            if (text.isBlank()) {
                break;
            }
            Matcher tagOnly = TAG_ONLY_LINE.matcher(text);
            if (tagOnly.matches()) {
                tag = tagOnly.group(1).trim();
                continue;
            }
            String content = text;
            Matcher trailingLine = TAG_TRAILING.matcher(content);
            if (trailingLine.find()) {
                tag = trailingLine.group(1).trim();
                content = content.substring(0, trailingLine.start()).trim();
            }
            content = content.trim();
            if (!content.isEmpty()) {
                if (!buf.isEmpty()) {
                    buf.append(' ');
                }
                buf.append(content);
            }
        }
        return new TagCapture(buf.toString(), tag);
    }

    private static String joinText(List<RawLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (RawLine line : lines) {
            String t = line.text().trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private static int indexOfFirstMatch(List<RawLine> lines, Pattern pattern) {
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i).text()).matches()) {
                return i;
            }
        }
        return -1;
    }

    private static void checkNumbering(List<Integer> numbers, String label, List<ParseIssue> issues) {
        Integer last = null;
        for (Integer n : numbers) {
            if (n == null) {
                continue;
            }
            if (last != null) {
                if (n.equals(last)) {
                    issues.add(ParseIssue.warning(ParseIssue.DUPLICATE_NUMBER, null,
                            label + " number " + n + " is duplicated"));
                } else if (n > last + 1) {
                    issues.add(ParseIssue.warning(ParseIssue.NUMBERING_GAP, null,
                            label + " numbering jumps from " + last + " to " + n));
                }
            }
            last = n;
        }
    }

    /** Strips a leading BOM, normalizes line endings/whitespace, and trims trailing whitespace per line. */
    private static String normalize(String raw) {
        String s = raw;
        if (!s.isEmpty() && s.charAt(0) == '﻿') {
            s = s.substring(1);
        }
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replace(' ', ' ').replace('\t', ' ');
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(stripTrailing(lines[i]));
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /* ---------------------------------------- line data ---------------------------------------- */

    private record RawLine(int lineNo, String text) {}

    private static final class RawBlock {
        final int startLine;
        final String kindHint;
        final Integer number;
        final boolean tiebreaker;
        final Mode headerMode;
        final List<RawLine> lines = new ArrayList<>();

        RawBlock(int startLine, String kindHint, Integer number, boolean tiebreaker, Mode headerMode) {
            this.startLine = startLine;
            this.kindHint = kindHint;
            this.number = number;
            this.tiebreaker = tiebreaker;
            this.headerMode = headerMode;
        }
    }
}

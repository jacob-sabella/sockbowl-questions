package com.soulsoftworks.sockbowlquestions.dto;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.packetio.ParseIssue;
import com.soulsoftworks.sockbowlquestions.packetio.ParseResult;

import java.util.List;

/**
 * GraphQL {@code ImportPacketResult} (plan 3.1.11). {@code parsed} is the parser's own
 * {@link ParseResult}: it carries an extra {@code issues} field GraphQL never queries
 * (only {@code ParsedPacket}'s {@code suggestedName}/{@code tossups}/{@code bonuses}
 * are declared), so no separate "ParsedPacketDto" is needed. Likewise {@link ParseIssue}
 * maps directly onto {@code ImportIssue} (its extra {@code code} field is simply never
 * queried).
 */
public record ImportPacketResultDto(boolean committed, Packet packet, ParseResult parsed, List<ParseIssue> issues) {

    public static ImportPacketResultDto preview(ParseResult parsed) {
        return new ImportPacketResultDto(false, null, parsed, parsed.issues());
    }

    public static ImportPacketResultDto refused(ParseResult parsed) {
        return new ImportPacketResultDto(false, null, parsed, parsed.issues());
    }

    public static ImportPacketResultDto committed(Packet packet, ParseResult parsed) {
        return new ImportPacketResultDto(true, packet, parsed, parsed.issues());
    }
}

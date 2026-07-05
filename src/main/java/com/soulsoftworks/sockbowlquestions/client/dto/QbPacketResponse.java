package com.soulsoftworks.sockbowlquestions.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response shape shared by qbreader's {@code /packet}, {@code /random-tossup} and
 * {@code /random-bonus} endpoints. For the random endpoints only one of the two
 * lists is populated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QbPacketResponse(
        List<QbTossup> tossups,
        List<QbBonus> bonuses
) {}

package com.soulsoftworks.sockbowlquestions.dto;

import java.util.List;

/** GraphQL {@code PacketPage} (plan 3.1.9, 3.1.11): one page of {@link PacketSummaryDto}. */
public record PacketPageDto(List<PacketSummaryDto> items, int total, int page, int size) {
}

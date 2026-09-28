package com.soulsoftworks.sockbowlquestions.packetio;

/**
 * The subset of {@code PacketLimitsProperties} the parser enforces while it reads text
 * (plan 3.1.1/3.1.8). A plain record, framework-free, so {@link PlaintextPacketParser}
 * has no Spring dependency; {@code service.PacketImportService} builds one from the
 * real {@code @ConfigurationProperties} bean.
 */
public record PacketLimits(int nameMax, int questionMax, int answerMax, int preambleMax,
                           int maxTossups, int maxBonuses, int maxPartsPerBonus, int minPartsPerBonus,
                           long importMaxBytes) {
}

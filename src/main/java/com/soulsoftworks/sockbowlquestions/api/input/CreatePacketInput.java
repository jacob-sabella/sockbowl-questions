package com.soulsoftworks.sockbowlquestions.api.input;

/**
 * Input for creating a new packet. Difficulty is optional at creation time.
 */
public record CreatePacketInput(String name, String difficultyId) {
}

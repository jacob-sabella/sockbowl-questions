package com.soulsoftworks.sockbowlquestions.api.input;

/**
 * Input for updating a bonus node's own scalar fields and subcategory.
 * Parts are managed via the dedicated bonus-part mutations.
 */
public record BonusUpdateInput(String preamble, String subcategoryId) {
}

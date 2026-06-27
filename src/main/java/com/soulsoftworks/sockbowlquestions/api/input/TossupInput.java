package com.soulsoftworks.sockbowlquestions.api.input;

/**
 * Input for creating or updating a tossup. Subcategory is optional.
 */
public record TossupInput(String question, String answer, String subcategoryId) {
}

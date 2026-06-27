package com.soulsoftworks.sockbowlquestions.api.input;

import java.util.List;

/**
 * Input for creating a bonus, optionally with its initial parts in order.
 */
public record BonusInput(String preamble, String subcategoryId, List<BonusPartInput> parts) {
}

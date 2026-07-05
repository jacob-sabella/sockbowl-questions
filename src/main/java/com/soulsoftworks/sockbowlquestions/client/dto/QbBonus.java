package com.soulsoftworks.sockbowlquestions.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A bonus as returned by the qbreader.org API. {@code parts} and {@code answers}
 * are parallel arrays (parts[i] is answered by answers[i]); qbreader's leadin maps
 * to sockbowl's bonus preamble.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QbBonus(
        String leadin,
        List<String> parts,
        List<String> answers,
        String category,
        String subcategory,
        Integer difficulty,
        Integer number
) {}

package com.soulsoftworks.sockbowlquestions.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A tossup as returned by the qbreader.org API. Only the fields sockbowl maps
 * are declared; everything else (ids, timestamps, sanitized variants) is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QbTossup(
        String question,
        String answer,
        String category,
        String subcategory,
        Integer difficulty,
        Integer number
) {}

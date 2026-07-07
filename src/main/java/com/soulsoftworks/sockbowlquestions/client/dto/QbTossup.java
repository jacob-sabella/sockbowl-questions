package com.soulsoftworks.sockbowlquestions.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record QbTossup(
        @JsonProperty("_id") String remoteId,
        String question,
        String answer,
        String category,
        String subcategory,
        @JsonProperty("alternate_subcategory") String alternateSubcategory,
        Integer difficulty,
        Integer number
) {}

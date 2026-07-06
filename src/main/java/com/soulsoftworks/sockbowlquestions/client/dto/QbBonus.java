package com.soulsoftworks.sockbowlquestions.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record QbBonus(
        @JsonProperty("_id") String remoteId,
        String leadin,
        List<String> parts,
        List<String> answers,
        String category,
        String subcategory,
        Integer difficulty,
        Integer number
) {}

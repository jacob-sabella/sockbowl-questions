package com.soulsoftworks.sockbowlquestions.client.dto;

import java.util.List;

/**
 * Filters for qbreader's random-tossup / random-bonus endpoints. All fields are
 * optional; null/empty means "no constraint".
 *
 * @param categories    qbreader category names
 * @param subcategories qbreader subcategory names
 * @param difficulties  qbreader 1-10 difficulty values
 * @param minYear       earliest set year (inclusive)
 * @param maxYear       latest set year (inclusive)
 * @param standardOnly  restrict to standard (non-trash/non-guerrilla) sets
 */
public record QbRandomFilter(
        List<String> categories,
        List<String> subcategories,
        List<Integer> difficulties,
        Integer minYear,
        Integer maxYear,
        Boolean standardOnly
) {}

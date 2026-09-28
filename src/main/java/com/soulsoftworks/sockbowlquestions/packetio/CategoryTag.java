package com.soulsoftworks.sockbowlquestions.packetio;

/**
 * Splits a plaintext category tag's raw content (the text inside {@code <...>}, e.g.
 * {@code "ED, Science - Biology"}) into a category name and an optional subcategory
 * name (plan 3.1.8). ACF puts author initials first, so only the last comma-separated
 * segment is taxonomy; that segment splits on the first {@code " - "}, {@code "/"} or
 * {@code ":"} it contains. Pure text processing: resolving the names against existing
 * taxonomy is {@code service.PacketImportService}'s job (import never creates taxonomy, D4).
 */
public final class CategoryTag {

    private CategoryTag() {}

    /** {@code subcategory} is null for a category-only tag (e.g. {@code "<Science>"}). */
    public record TagParts(String category, String subcategory) {}

    public static TagParts split(String rawTagContent) {
        if (rawTagContent == null) {
            return null;
        }
        String trimmed = rawTagContent.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] commaParts = trimmed.split(",");
        String last = commaParts[commaParts.length - 1].trim();
        if (last.isEmpty()) {
            return null;
        }
        String[] segments = last.split(" - |/|:", 2);
        String category = segments[0].trim();
        if (category.isEmpty()) {
            return null;
        }
        String subcategory = segments.length > 1 ? segments[1].trim() : null;
        return new TagParts(category, (subcategory == null || subcategory.isEmpty()) ? null : subcategory);
    }
}

package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.config.PacketLimitsProperties;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.exception.ValidationFailedException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Taxonomy authoring: difficulties, categories and subcategories (PB-10, D4). Split out
 * of {@link PacketAuthoringService} in M3 Q1 with the create methods unchanged; Q4
 * implements plan 3.1.6 in full:
 *
 * <ul>
 *   <li><b>Create</b> is idempotent and case-insensitive, keyed on {@code nameKey}
 *       ({@code toLower(trim(name))}). A create that matches an existing node returns
 *       that node rather than duplicating it.
 *   <li><b>Rename</b> rejects a name that would collide with another node's
 *       {@code nameKey}, and suggests merge instead.
 *   <li><b>Merge</b> re-points every relationship from the source node to the target,
 *       then deletes the source, all in one transaction. Merging a node into itself, or
 *       subcategories across different categories, is rejected. Merging categories moves
 *       the source's subcategories to the target, merging any same-key pairs
 *       recursively. There is no delete mutation (D4); merge is the only destructive
 *       taxonomy operation, and it is never automatic (see
 *       {@code TaxonomySchemaInitializer}).
 * </ul>
 *
 * <p>The bank nodes ({@code BankTossup}/{@code BankBonus}/{@code BankBonusPart}) hold
 * category/subcategory/difficulty as plain string properties, not relationships to
 * these nodes (confirmed by the M3 Q0 scout), so merge has nothing to re-point there.
 */
@Service
public class TaxonomyService {

    private final DifficultyRepository difficultyRepository;
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final PacketLimitsProperties limits;
    private final Neo4jClient neo4j;

    public TaxonomyService(DifficultyRepository difficultyRepository,
                           CategoryRepository categoryRepository,
                           SubcategoryRepository subcategoryRepository,
                           PacketLimitsProperties limits,
                           Neo4jClient neo4j) {
        this.difficultyRepository = difficultyRepository;
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.limits = limits;
        this.neo4j = neo4j;
    }

    @Transactional
    public Difficulty createDifficulty(String name) {
        String trimmed = requireText(name, "Difficulty name");
        String key = nameKey(trimmed);
        String id = difficultyRepository.mergeCreateByNameKey(key, trimmed);
        return difficultyRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Difficulty", id));
    }

    @Transactional
    public Category createCategory(String name) {
        String trimmed = requireText(name, "Category name");
        String key = nameKey(trimmed);
        String id = categoryRepository.mergeCreateByNameKey(key, trimmed);
        return categoryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Category", id));
    }

    @Transactional
    public Subcategory createSubcategory(String name, String categoryId) {
        String trimmed = requireText(name, "Subcategory name");
        String key = nameKey(trimmed);
        String id = subcategoryRepository.mergeCreateByNameKey(categoryId, key, trimmed);
        if (id == null) {
            throw ResourceNotFoundException.of("Category", categoryId);
        }
        return subcategoryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Subcategory", id));
    }

    @Transactional
    public Category renameCategory(String id, String name) {
        categoryRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Category", id));
        String trimmed = requireText(name, "Category name");
        String key = nameKey(trimmed);
        rejectCollision(categoryRepository.findByNameKey(key).map(Category::getId), id, trimmed, "category");
        categoryRepository.renameById(id, trimmed, key);
        return categoryRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Category", id));
    }

    @Transactional
    public Difficulty renameDifficulty(String id, String name) {
        difficultyRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Difficulty", id));
        String trimmed = requireText(name, "Difficulty name");
        String key = nameKey(trimmed);
        rejectCollision(difficultyRepository.findByNameKey(key).map(Difficulty::getId), id, trimmed, "difficulty");
        difficultyRepository.renameById(id, trimmed, key);
        return difficultyRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Difficulty", id));
    }

    /** Longest difficulty description accepted; it goes into every generation prompt. */
    public static final int DESCRIPTION_MAX = 2000;

    /** Sets a difficulty's generation description; null or blank clears it. */
    @Transactional
    public Difficulty setDifficultyDescription(String id, String description) {
        difficultyRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Difficulty", id));
        String trimmed = description == null ? "" : description.strip();
        if (trimmed.length() > DESCRIPTION_MAX) {
            throw new ValidationFailedException("description",
                    "Difficulty description exceeds " + DESCRIPTION_MAX + " characters");
        }
        difficultyRepository.setDescriptionById(id, trimmed);
        return difficultyRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Difficulty", id));
    }

    @Transactional
    public Subcategory renameSubcategory(String id, String name) {
        Subcategory current = subcategoryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Subcategory", id));
        String categoryId = current.getCategory() != null ? current.getCategory().getId() : null;
        String trimmed = requireText(name, "Subcategory name");
        String key = nameKey(trimmed);
        if (categoryId != null) {
            rejectCollision(subcategoryRepository.findIdByNameKeyAndCategoryId(key, categoryId), id, trimmed,
                    "subcategory");
        }
        subcategoryRepository.renameById(id, trimmed, key);
        return subcategoryRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Subcategory", id));
    }

    @Transactional
    public Category mergeCategories(String sourceId, String targetId) {
        rejectSelfMerge(sourceId, targetId);
        categoryRepository.findById(sourceId).orElseThrow(() -> ResourceNotFoundException.of("Category", sourceId));
        categoryRepository.findById(targetId).orElseThrow(() -> ResourceNotFoundException.of("Category", targetId));

        for (Map<String, Object> ref : subcategoryRefsUnderCategory(sourceId)) {
            String subId = String.valueOf(ref.get("id"));
            String subKey = (String) ref.get("nameKey");
            Optional<String> sameKeyUnderTarget = subKey == null ? Optional.empty()
                    : subcategoryRepository.findIdByNameKeyAndCategoryId(subKey, targetId);
            if (sameKeyUnderTarget.isPresent()) {
                // Same-key pair: merge the source subcategory (and its tossups/bonuses)
                // into the one that already exists under the target category, rather
                // than moving it over and ending up with two subcategories of the same
                // name under one category.
                subcategoryRepository.mergeInto(subId, sameKeyUnderTarget.get());
            } else {
                subcategoryRepository.repointToCategory(subId, targetId);
            }
        }
        categoryRepository.deleteByIdCascade(sourceId);
        return categoryRepository.findById(targetId).orElseThrow(() -> ResourceNotFoundException.of("Category", targetId));
    }

    @Transactional
    public Subcategory mergeSubcategories(String sourceId, String targetId) {
        rejectSelfMerge(sourceId, targetId);
        Subcategory source = subcategoryRepository.findById(sourceId)
                .orElseThrow(() -> ResourceNotFoundException.of("Subcategory", sourceId));
        Subcategory target = subcategoryRepository.findById(targetId)
                .orElseThrow(() -> ResourceNotFoundException.of("Subcategory", targetId));
        String sourceCategoryId = source.getCategory() != null ? source.getCategory().getId() : null;
        String targetCategoryId = target.getCategory() != null ? target.getCategory().getId() : null;
        if (!Objects.equals(sourceCategoryId, targetCategoryId)) {
            throw new ValidationFailedException("targetId", "Cannot merge subcategories across different categories");
        }
        subcategoryRepository.mergeInto(sourceId, targetId);
        return subcategoryRepository.findById(targetId)
                .orElseThrow(() -> ResourceNotFoundException.of("Subcategory", targetId));
    }

    @Transactional
    public Difficulty mergeDifficulties(String sourceId, String targetId) {
        rejectSelfMerge(sourceId, targetId);
        difficultyRepository.findById(sourceId).orElseThrow(() -> ResourceNotFoundException.of("Difficulty", sourceId));
        difficultyRepository.findById(targetId).orElseThrow(() -> ResourceNotFoundException.of("Difficulty", targetId));
        difficultyRepository.mergeInto(sourceId, targetId);
        return difficultyRepository.findById(targetId)
                .orElseThrow(() -> ResourceNotFoundException.of("Difficulty", targetId));
    }

    /**
     * The id and nameKey of every subcategory directly under {@code categoryId}, for
     * {@link #mergeCategories} to decide which of the source category's subcategories
     * collide with one already under the target and which can simply be repointed.
     * Uses {@link Neo4jClient} directly rather than a repository {@code @Query}: Spring
     * Data Neo4j's repository queries only map a single-column result to a simple return
     * type (a multi-column {@code List<Map<String,Object>>} throws
     * {@code IllegalArgumentException} against a real Neo4j), while
     * {@code Neo4jClient.fetch().all()} returns {@code Collection<Map<String,Object>>}
     * natively.
     */
    private Collection<Map<String, Object>> subcategoryRefsUnderCategory(String categoryId) {
        return neo4j.query("""
                        MATCH (s:Subcategory)-[:SUBCATEGORY_OF]->(c:Category {id: $categoryId})
                        RETURN s.id AS id, s.nameKey AS nameKey
                        """)
                .bind(categoryId).to("categoryId")
                .fetch().all();
    }

    private static void rejectSelfMerge(String sourceId, String targetId) {
        if (Objects.equals(sourceId, targetId)) {
            throw new ValidationFailedException("targetId", "Cannot merge a taxonomy entry into itself");
        }
    }

    private static void rejectCollision(Optional<String> collidingId, String ownId, String name, String kind) {
        collidingId.filter(id -> !id.equals(ownId)).ifPresent(id -> {
            throw new ValidationFailedException("name",
                    "A " + kind + " named '" + name + "' already exists; merge into it instead of renaming");
        });
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidApiRequestException(field + " must not be blank");
        }
        String trimmed = value.trim();
        int max = limits.getTaxonomy().getNameMax();
        if (trimmed.length() > max) {
            throw new ValidationFailedException("name", field + " exceeds " + max + " characters");
        }
        return trimmed;
    }

    private static String nameKey(String trimmedValue) {
        return trimmedValue.toLowerCase(Locale.ROOT);
    }
}

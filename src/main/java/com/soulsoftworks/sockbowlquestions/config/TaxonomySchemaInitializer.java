package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import com.soulsoftworks.sockbowlquestions.service.TaxonomyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Startup taxonomy housekeeping (M3 Q4, plan 3.1.6 step 3, D4). Runs once per boot,
 * in this order:
 *
 * <ol>
 *   <li>Backfill {@code nameKey} on every {@code Category}/{@code Subcategory}/
 *       {@code Difficulty} node that lacks one. Non-destructive: it only ever fills in
 *       a null.
 *   <li>For categories and difficulties: if no two nodes share a {@code nameKey},
 *       create the {@code category_namekey}/{@code difficulty_namekey} uniqueness
 *       constraints ({@code IF NOT EXISTS}). If duplicates exist, log a {@code WARN}
 *       naming each group and skip the constraint, unless...
 *   <li>...{@code sockbowl.packet.taxonomy.dedupe-on-startup=true}, in which case each
 *       duplicate group is merged (via {@link TaxonomyService}, so subcategories,
 *       tossups and bonuses are re-pointed the same way the admin merge action does)
 *       before the constraint is created.
 * </ol>
 *
 * <p>There is no per-category subcategory uniqueness constraint: subcategory identity
 * is scoped by category (a {@code SUBCATEGORY_OF} relationship), which a plain Neo4j
 * property constraint cannot express, so only its {@code nameKey} is backfilled here.
 *
 * <p>Dedupe defaults to off (matching {@link PacketLimitsProperties.Taxonomy}): nothing
 * destructive runs without the operator opting in. A failure here never blocks startup;
 * {@code TaxonomyService.createCategory}/{@code createDifficulty} MERGE by
 * {@code nameKey} regardless of whether the constraint exists, so creation stays
 * idempotent either way — the constraint is a data-integrity backstop, not something
 * correctness depends on.
 */
@Slf4j
@Component
public class TaxonomySchemaInitializer implements ApplicationRunner {

    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final DifficultyRepository difficultyRepository;
    private final TaxonomyService taxonomyService;
    private final PacketLimitsProperties limits;
    private final Neo4jClient neo4j;

    public TaxonomySchemaInitializer(CategoryRepository categoryRepository,
                                     SubcategoryRepository subcategoryRepository,
                                     DifficultyRepository difficultyRepository,
                                     TaxonomyService taxonomyService,
                                     PacketLimitsProperties limits,
                                     Neo4jClient neo4j) {
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.difficultyRepository = difficultyRepository;
        this.taxonomyService = taxonomyService;
        this.limits = limits;
        this.neo4j = neo4j;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            initialize();
        } catch (RuntimeException e) {
            log.warn("Taxonomy schema initialization failed; nameKey backfill and/or the uniqueness "
                    + "constraints may be incomplete until the next start", e);
        }
    }

    /** Runs the backfill, then the per-kind duplicate check/dedupe/constraint step. */
    public void initialize() {
        backfillNameKeys();
        boolean dedupe = limits.getTaxonomy().isDedupeOnStartup();
        reconcile("Category", duplicateNameKeyGroups("Category"), dedupe,
                taxonomyService::mergeCategories, categoryRepository::createNameKeyConstraint);
        reconcile("Difficulty", duplicateNameKeyGroups("Difficulty"), dedupe,
                taxonomyService::mergeDifficulties, difficultyRepository::createNameKeyConstraint);
    }

    /**
     * Groups of {@code kind} (a Neo4j label: {@code Category} or {@code Difficulty})
     * nodes sharing a {@code nameKey}. Each row: {@code key} (the shared nameKey),
     * {@code ids}, {@code names}. Uses {@link Neo4jClient} directly rather than a
     * repository {@code @Query}: Spring Data Neo4j's repository queries only map a
     * single-column result to a simple return type (a multi-column
     * {@code List<Map<String,Object>>} throws {@code IllegalArgumentException} against a
     * real Neo4j), while {@code Neo4jClient.fetch().all()} returns
     * {@code Collection<Map<String,Object>>} natively. {@code kind} is only ever one of
     * the two label literals above, both trusted constants, so string-building the label
     * into the query is safe.
     */
    private Collection<Map<String, Object>> duplicateNameKeyGroups(String kind) {
        return neo4j.query("""
                        MATCH (n:%s) WHERE n.nameKey IS NOT NULL
                        WITH n.nameKey AS key, collect(n.id) AS ids, collect(n.name) AS names
                        WHERE size(ids) > 1
                        RETURN key, ids, names
                        """.formatted(kind))
                .fetch().all();
    }

    private void backfillNameKeys() {
        long categories = categoryRepository.backfillNameKeys();
        long subcategories = subcategoryRepository.backfillNameKeys();
        long difficulties = difficultyRepository.backfillNameKeys();
        if (categories + subcategories + difficulties > 0) {
            log.info("Taxonomy nameKey backfill: {} categor{}, {} subcategor{}, {} difficult{}",
                    categories, categories == 1 ? "y" : "ies",
                    subcategories, subcategories == 1 ? "y" : "ies",
                    difficulties, difficulties == 1 ? "y" : "ies");
        }
    }

    private void reconcile(String kind, Collection<Map<String, Object>> duplicateGroups, boolean dedupe,
                           BiConsumer<String, String> mergeSourceIntoTarget, Runnable createConstraint) {
        if (duplicateGroups.isEmpty()) {
            createConstraint.run();
            return;
        }
        if (!dedupe) {
            for (Map<String, Object> group : duplicateGroups) {
                log.warn("Duplicate {} name '{}': {} (ids {}). Merge them from the taxonomy admin page, or set "
                                + "sockbowl.packet.taxonomy.dedupe-on-startup=true to merge automatically on start.",
                        kind, group.get("key"), group.get("names"), group.get("ids"));
            }
            log.warn("Skipping the {} nameKey uniqueness constraint until the duplicates above are resolved", kind);
            return;
        }
        for (Map<String, Object> group : duplicateGroups) {
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) group.get("ids");
            String target = ids.get(0);
            for (int i = 1; i < ids.size(); i++) {
                mergeSourceIntoTarget.accept(ids.get(i), target);
            }
            log.info("Merged {} duplicate {} node(s) with name '{}' into {}", ids.size() - 1, kind,
                    group.get("key"), target);
        }
        createConstraint.run();
    }
}

package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import com.soulsoftworks.sockbowlquestions.service.TaxonomyService;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaxonomySchemaInitializer} against a real Neo4j (M3 Q4, plan 3.1.6 step 3).
 *
 * <p>The {@code category_namekey}/{@code difficulty_namekey} constraints are database-
 * global, so each test that needs to observe "no constraint" first drops it, then
 * restores it (directly, or by letting {@code initialize()} recreate it) before the test
 * ends, so tests stay order-independent within the shared container. Every node this
 * class creates is tagged {@code {t: 'tsi'}} for cleanup, and duplicate-nameKey pairs use
 * a random UUID as the shared name/nameKey so they can never collide with real taxonomy
 * data elsewhere in the shared container.
 */
@SpringBootTest
class TaxonomySchemaInitializerIT extends Neo4jContainerTestBase {

    private static final String TAG = "tsi";

    @Autowired private TaxonomySchemaInitializer initializer;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private SubcategoryRepository subcategoryRepository;
    @Autowired private DifficultyRepository difficultyRepository;
    @Autowired private TaxonomyService taxonomyService;
    @Autowired private Neo4jClient neo4j;

    @BeforeEach
    @AfterEach
    void clean() {
        neo4j.query("MATCH (n {t: $tag}) DETACH DELETE n").bind(TAG).to("tag").run();
    }

    @Test
    void backfillsNameKeyOnEveryKind() {
        neo4j.query("""
                CREATE (cat:Category {t: $tag, id: 'tsi-cat', name: 'TsiCategory'})
                CREATE (cat)<-[:SUBCATEGORY_OF]-(:Subcategory {t: $tag, id: 'tsi-sub', name: 'TsiSub'})
                CREATE (:Difficulty {t: $tag, id: 'tsi-diff', name: 'TsiDiff'})
                """).bind(TAG).to("tag").run();

        initializer.initialize();

        assertThat(nameKeyOf("Category", "tsi-cat")).isEqualTo("tsicategory");
        assertThat(nameKeyOf("Subcategory", "tsi-sub")).isEqualTo("tsisub");
        assertThat(nameKeyOf("Difficulty", "tsi-diff")).isEqualTo("tsidiff");
    }

    @Test
    void createsConstraintWhenNoDuplicatesExist() {
        neo4j.query("DROP CONSTRAINT category_namekey IF EXISTS").run();
        String uniqueKey = "tsi-clean-" + UUID.randomUUID();
        neo4j.query("CREATE (:Category {t: $tag, id: 'tsi-unique-cat', name: $name, nameKey: $key})")
                .bind(TAG).to("tag").bind(uniqueKey).to("name").bind(uniqueKey).to("key").run();

        initializer.initialize();

        assertThat(constraintExists("category_namekey")).isTrue();
    }

    @Test
    void skipsConstraintAndLeavesDuplicatesWhenDedupeIsOff() {
        neo4j.query("DROP CONSTRAINT category_namekey IF EXISTS").run();
        String dupKey = "tsi-dup-" + UUID.randomUUID();
        neo4j.query("""
                CREATE (:Category {t: $tag, id: 'tsi-dupA', name: $name, nameKey: $key})
                CREATE (:Category {t: $tag, id: 'tsi-dupB', name: $name, nameKey: $key})
                """).bind(TAG).to("tag").bind(dupKey).to("name").bind(dupKey).to("key").run();

        initializer.initialize();

        assertThat(constraintExists("category_namekey")).isFalse();
        assertThat(nodeExists("tsi-dupA")).isTrue();
        assertThat(nodeExists("tsi-dupB")).isTrue();

        // Restore the shared constraint for order-independence: resolve the duplicate
        // pair ourselves (the same way the admin merge action would) and re-run.
        taxonomyService.mergeCategories("tsi-dupB", "tsi-dupA");
        initializer.initialize();
        assertThat(constraintExists("category_namekey")).isTrue();
    }

    @Test
    void dedupeOnStartupMergesDuplicatesThenCreatesConstraint() {
        neo4j.query("DROP CONSTRAINT category_namekey IF EXISTS").run();
        String dupKey = "tsi-dedupe-" + UUID.randomUUID();
        neo4j.query("""
                CREATE (:Category {t: $tag, id: 'tsi-dedupeA', name: $name, nameKey: $key})
                CREATE (:Category {t: $tag, id: 'tsi-dedupeB', name: $name, nameKey: $key})
                """).bind(TAG).to("tag").bind(dupKey).to("name").bind(dupKey).to("key").run();

        PacketLimitsProperties dedupeOn = new PacketLimitsProperties();
        dedupeOn.getTaxonomy().setDedupeOnStartup(true);
        TaxonomySchemaInitializer dedupeInitializer = new TaxonomySchemaInitializer(
                categoryRepository, subcategoryRepository, difficultyRepository, taxonomyService, dedupeOn, neo4j);

        dedupeInitializer.initialize();

        assertThat(constraintExists("category_namekey")).isTrue();
        long remaining = neo4j.query("MATCH (c:Category {nameKey: $key}) RETURN c.id AS id")
                .bind(dupKey).to("key").fetch().all().size();
        assertThat(remaining).isEqualTo(1L);
    }

    private String nameKeyOf(String label, String id) {
        return (String) neo4j.query("MATCH (n:" + label + " {id: $id}) RETURN n.nameKey AS v")
                .bind(id).to("id").fetch().all().stream().findFirst()
                .orElseThrow(() -> new AssertionError("no " + label + " with id " + id))
                .get("v");
    }

    private boolean constraintExists(String name) {
        return !neo4j.query("SHOW CONSTRAINTS YIELD name WHERE name = $name RETURN name")
                .bind(name).to("name").fetch().all().isEmpty();
    }

    private boolean nodeExists(String id) {
        return !neo4j.query("MATCH (n {id: $id}) RETURN n.id").bind(id).to("id").fetch().all().isEmpty();
    }
}

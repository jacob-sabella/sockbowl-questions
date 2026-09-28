package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.service.TaxonomyService;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q-M3V1-03: the taxonomy "MERGE by nameKey" writes stay one-row-per-input when legacy
 * case-variant duplicates exist. {@code TaxonomySchemaInitializer} deliberately leaves
 * such duplicates in place (and skips the uniqueness constraint) unless
 * {@code dedupe-on-startup} is on, so {@code batchCreatePacket} (import-random) and
 * {@code Category/Difficulty/SubcategoryRepository.mergeCreateByNameKey} must resolve to
 * one node (the lowest id) instead of fanning out over every match.
 *
 * <p>The {@code category_namekey}/{@code difficulty_namekey} constraints are database-global
 * in the shared container, so each test drops them to seed duplicates and
 * {@link #restoreConstraints()} deletes this class's nodes and recreates them.
 */
@SpringBootTest
class TaxonomyNameKeyDuplicatesCypherIT extends Neo4jContainerTestBase {

    private static final String TAG = "q3dup";
    /** INT1: M4's batchCreatePacket audit parameters ($auditor, $nowIso, $source). */
    private static final String NOW_ISO = Instant.now().toString();

    @Autowired private Neo4jClient neo4j;
    @Autowired private PacketRepository packetRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private DifficultyRepository difficultyRepository;
    @Autowired private SubcategoryRepository subcategoryRepository;
    @Autowired private TaxonomyService taxonomyService;

    private String suffix;

    @BeforeEach
    void dropConstraints() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        neo4j.query("DROP CONSTRAINT category_namekey IF EXISTS").run();
        neo4j.query("DROP CONSTRAINT difficulty_namekey IF EXISTS").run();
    }

    @AfterEach
    void restoreConstraints() {
        neo4j.query("""
                MATCH (p:Packet) WHERE p.name STARTS WITH $tag
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP|CONTAINS_BONUS]->(item)
                OPTIONAL MATCH (item)-[:HAS_PART]->(part)
                DETACH DELETE p, item, part
                """).bind(TAG).to("tag").run();
        neo4j.query("""
                MATCH (n) WHERE (n:Category OR n:Subcategory OR n:Difficulty) AND n.nameKey STARTS WITH $tag
                DETACH DELETE n
                """).bind(TAG).to("tag").run();
        categoryRepository.createNameKeyConstraint();
        difficultyRepository.createNameKeyConstraint();
    }

    @Test
    void batchCreatePacketResolvesCaseVariantDuplicatesToOneNodeEach() {
        String cat = TAG + "-Science-" + suffix;
        String diff = TAG + "-Easy-" + suffix;
        String sub = TAG + "-Physics-" + suffix;
        neo4j.query("""
                CREATE (c1:Category {id: $p + '-cat-1', name: $cat, nameKey: toLower($cat)})
                CREATE (c2:Category {id: $p + '-cat-2', name: toLower($cat), nameKey: toLower($cat)})
                CREATE (c1)<-[:SUBCATEGORY_OF]-(:Subcategory {id: $p + '-sub-1', name: $sub, nameKey: toLower($sub)})
                CREATE (c2)<-[:SUBCATEGORY_OF]-(:Subcategory {id: $p + '-sub-2', name: $sub, nameKey: toLower($sub)})
                CREATE (:Difficulty {id: $p + '-diff-1', name: $diff, nameKey: toLower($diff)})
                CREATE (:Difficulty {id: $p + '-diff-2', name: toLower($diff), nameKey: toLower($diff)})
                """).bindAll(Map.of("p", TAG + suffix, "cat", cat, "sub", sub, "diff", diff)).run();

        String packetName = TAG + " packet " + suffix;
        String packetId = packetRepository.batchCreatePacket(packetName, diff,
                List.of(tossupRow(cat, sub, 0)),
                List.of(bonusRow(cat, sub, 0)),
                "owner", "Owner", PacketVisibility.DRAFT.name(), "import-random",
                "owner", NOW_ISO, ContentSource.QBREADER_IMPORT.name());

        assertThat(count("MATCH (p:Packet {name: $v}) RETURN count(p) AS n", packetName)).isEqualTo(1);
        assertThat(count("MATCH (:Packet {id: $v})-[:CONTAINS_TOSSUP]->(t) RETURN count(t) AS n", packetId)).isEqualTo(1);
        assertThat(count("MATCH (:Packet {id: $v})-[:CONTAINS_BONUS]->(b) RETURN count(b) AS n", packetId)).isEqualTo(1);
        assertThat(count("MATCH (:Packet {id: $v})-[:CONTAINS_BONUS]->()-[:HAS_PART]->(bp) RETURN count(bp) AS n",
                packetId)).isEqualTo(1);
        assertThat(count("MATCH (:Packet {id: $v})-[:DIFFICULTY_LEVEL]->(d) RETURN count(d) AS n", packetId)).isEqualTo(1);
        assertThat(single("MATCH (:Packet {id: $v})-[:DIFFICULTY_LEVEL]->(d) RETURN d.id AS n", packetId))
                .isEqualTo(TAG + suffix + "-diff-1");
        // Lowest-id category wins, and its own subcategory is reused.
        assertThat(single("MATCH (:Packet {id: $v})-[:CONTAINS_TOSSUP]->()-[:SUBCATEGORY_IS]->(s) RETURN s.id AS n",
                packetId)).isEqualTo(TAG + suffix + "-sub-1");
        // INT1: the M4 audit SETs survive alongside the M3 taxonomy resolution.
        assertThat(single("MATCH (:Packet {id: $v})-[:CONTAINS_TOSSUP]->(t) RETURN t.source + '|' + t.createdBy AS n",
                packetId)).isEqualTo("QBREADER_IMPORT|owner");
        assertThat(single("MATCH (:Packet {id: $v})-[:CONTAINS_BONUS]->()-[:HAS_PART]->(bp) RETURN bp.source + '|' + bp.lastModifiedBy AS n",
                packetId)).isEqualTo("QBREADER_IMPORT|owner");
        // Nothing new was created for a key that already had nodes.
        assertThat(count("MATCH (c:Category {nameKey: $v}) RETURN count(c) AS n", cat.toLowerCase())).isEqualTo(2);
        assertThat(count("MATCH (d:Difficulty {nameKey: $v}) RETURN count(d) AS n", diff.toLowerCase())).isEqualTo(2);
    }

    @Test
    void batchCreatePacketResolvesDuplicateSubcategoriesUnderOneCategory() {
        String cat = TAG + "-Chem-" + suffix;
        String sub = TAG + "-Organic-" + suffix;
        neo4j.query("""
                CREATE (c:Category {id: $p + '-cat', name: $cat, nameKey: toLower($cat)})
                CREATE (c)<-[:SUBCATEGORY_OF]-(:Subcategory {id: $p + '-sub-1', name: $sub, nameKey: toLower($sub)})
                CREATE (c)<-[:SUBCATEGORY_OF]-(:Subcategory {id: $p + '-sub-2', name: toLower($sub), nameKey: toLower($sub)})
                """).bindAll(Map.of("p", TAG + suffix, "cat", cat, "sub", sub)).run();

        String packetId = packetRepository.batchCreatePacket(TAG + " subdup " + suffix, TAG + "-Diff-" + suffix,
                List.of(tossupRow(cat, sub, 0)), List.of(bonusRow(cat, sub, 0)),
                null, null, PacketVisibility.DRAFT.name(), null,
                null, NOW_ISO, ContentSource.QBREADER_IMPORT.name());

        assertThat(count("MATCH (:Packet {id: $v})-[:CONTAINS_TOSSUP]->(t) RETURN count(t) AS n", packetId)).isEqualTo(1);
        assertThat(count("MATCH (:Packet {id: $v})-[:CONTAINS_BONUS]->(b) RETURN count(b) AS n", packetId)).isEqualTo(1);
        assertThat(single("MATCH (:Packet {id: $v})-[:CONTAINS_TOSSUP]->()-[:SUBCATEGORY_IS]->(s) RETURN s.id AS n",
                packetId)).isEqualTo(TAG + suffix + "-sub-1");
    }

    @Test
    void batchCreatePacketCreatesANewNameOnceEvenWhenSeveralRowsShareIt() {
        String cat = TAG + "-Fresh-" + suffix;
        String sub = TAG + "-FreshSub-" + suffix;
        String diff = TAG + "-FreshDiff-" + suffix;

        String packetId = packetRepository.batchCreatePacket(TAG + " fresh " + suffix, diff,
                List.of(tossupRow(cat, sub, 0), tossupRow(cat.toLowerCase(), sub, 1), tossupRow(cat, sub, 2)),
                List.of(bonusRow(cat, sub, 0), bonusRow(cat.toUpperCase(), sub, 1)),
                null, null, PacketVisibility.DRAFT.name(), null,
                null, NOW_ISO, ContentSource.QBREADER_IMPORT.name());

        assertThat(count("MATCH (:Packet {id: $v})-[:CONTAINS_TOSSUP]->(t) RETURN count(t) AS n", packetId)).isEqualTo(3);
        assertThat(count("MATCH (:Packet {id: $v})-[:CONTAINS_BONUS]->(b) RETURN count(b) AS n", packetId)).isEqualTo(2);
        assertThat(count("MATCH (c:Category {nameKey: $v}) RETURN count(c) AS n", cat.toLowerCase())).isEqualTo(1);
        assertThat(count("MATCH (s:Subcategory {nameKey: $v}) RETURN count(s) AS n", sub.toLowerCase())).isEqualTo(1);
        assertThat(count("MATCH (d:Difficulty {nameKey: $v}) RETURN count(d) AS n", diff.toLowerCase())).isEqualTo(1);
        assertThat(single("MATCH (c:Category {nameKey: $v}) RETURN c.name AS n", cat.toLowerCase())).isEqualTo(cat);
    }

    @Test
    void mergeCreateByNameKeyReturnsOneExistingNodeWhenDuplicatesExist() {
        String cat = TAG + "-History-" + suffix;
        String diff = TAG + "-Hard-" + suffix;
        String sub = TAG + "-Ancient-" + suffix;
        neo4j.query("""
                CREATE (c1:Category {id: $p + '-cat-1', name: $cat, nameKey: toLower($cat)})
                CREATE (:Category {id: $p + '-cat-2', name: toLower($cat), nameKey: toLower($cat)})
                CREATE (c1)<-[:SUBCATEGORY_OF]-(:Subcategory {id: $p + '-sub-1', name: $sub, nameKey: toLower($sub)})
                CREATE (c1)<-[:SUBCATEGORY_OF]-(:Subcategory {id: $p + '-sub-2', name: toLower($sub), nameKey: toLower($sub)})
                CREATE (:Difficulty {id: $p + '-diff-1', name: $diff, nameKey: toLower($diff)})
                CREATE (:Difficulty {id: $p + '-diff-2', name: toLower($diff), nameKey: toLower($diff)})
                """).bindAll(Map.of("p", TAG + suffix, "cat", cat, "sub", sub, "diff", diff)).run();

        assertThat(categoryRepository.mergeCreateByNameKey(cat.toLowerCase(), cat)).isEqualTo(TAG + suffix + "-cat-1");
        assertThat(difficultyRepository.mergeCreateByNameKey(diff.toLowerCase(), diff)).isEqualTo(TAG + suffix + "-diff-1");
        assertThat(subcategoryRepository.mergeCreateByNameKey(TAG + suffix + "-cat-1", sub.toLowerCase(), sub))
                .isEqualTo(TAG + suffix + "-sub-1");
        assertThat(taxonomyService.createCategory(cat.toUpperCase()).getId()).isEqualTo(TAG + suffix + "-cat-1");
        assertThat(taxonomyService.createDifficulty(" " + diff + " ").getId()).isEqualTo(TAG + suffix + "-diff-1");

        assertThat(count("MATCH (c:Category {nameKey: $v}) RETURN count(c) AS n", cat.toLowerCase())).isEqualTo(2);
        assertThat(count("MATCH (d:Difficulty {nameKey: $v}) RETURN count(d) AS n", diff.toLowerCase())).isEqualTo(2);
        assertThat(count("MATCH (s:Subcategory {nameKey: $v}) RETURN count(s) AS n", sub.toLowerCase())).isEqualTo(2);
    }

    @Test
    void mergeCreateByNameKeyStillCreatesWhenAbsentAndIsIdempotent() {
        String cat = TAG + "-NewCat-" + suffix;
        String diff = TAG + "-NewDiff-" + suffix;

        String catId = categoryRepository.mergeCreateByNameKey(cat.toLowerCase(), cat);
        String diffId = difficultyRepository.mergeCreateByNameKey(diff.toLowerCase(), diff);
        String subId = subcategoryRepository.mergeCreateByNameKey(catId, (TAG + "-newsub-" + suffix), TAG + "-NewSub-" + suffix);

        assertThat(catId).isNotBlank();
        assertThat(diffId).isNotBlank();
        assertThat(subId).isNotBlank();
        assertThat(categoryRepository.mergeCreateByNameKey(cat.toLowerCase(), cat.toUpperCase())).isEqualTo(catId);
        assertThat(difficultyRepository.mergeCreateByNameKey(diff.toLowerCase(), diff)).isEqualTo(diffId);
        assertThat(subcategoryRepository.mergeCreateByNameKey(catId, (TAG + "-newsub-" + suffix), "x")).isEqualTo(subId);
        assertThat(subcategoryRepository.mergeCreateByNameKey(TAG + "-missing-" + suffix, "k", "n")).isNull();
        assertThat(count("MATCH (c:Category {nameKey: $v}) RETURN count(c) AS n", cat.toLowerCase())).isEqualTo(1);
        assertThat(single("MATCH (c:Category {nameKey: $v}) RETURN c.name AS n", cat.toLowerCase())).isEqualTo(cat);
    }

    /* --------------------------------- helpers -------------------------------- */

    private static Map<String, Object> tossupRow(String category, String subcategory, int order) {
        return Map.of("question", "Q" + order + "?", "answer", "A" + order,
                "category", category, "subcategory", subcategory, "remoteId", "", "order", order);
    }

    private static Map<String, Object> bonusRow(String category, String subcategory, int order) {
        return Map.of("preamble", "For ten points:", "category", category, "subcategory", subcategory,
                "remoteId", "", "order", order,
                "parts", List.of(Map.of("question", "Part?", "answer", "Part answer", "order", 0)));
    }

    private long count(String cypher, String value) {
        return neo4j.query(cypher).bind(value).to("v").fetchAs(Long.class).one().orElse(-1L);
    }

    private String single(String cypher, String value) {
        return neo4j.query(cypher).bind(value).to("v").fetchAs(String.class).one().orElse(null);
    }
}

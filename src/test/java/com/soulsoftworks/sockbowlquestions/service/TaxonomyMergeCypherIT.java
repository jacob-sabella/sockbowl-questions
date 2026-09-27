package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Taxonomy dedupe/rename/merge Cypher against a real Neo4j (M3 Q4, plan 3.1.6). Every
 * node this class creates is tagged {@code {t: 'txn-merge'}} so cleanup is a single
 * {@code MATCH (n {t: $tag}) DETACH DELETE n}, regardless of label; the shared
 * container is reused across test classes.
 */
@SpringBootTest
class TaxonomyMergeCypherIT extends Neo4jContainerTestBase {

    private static final String TAG = "txn-merge";

    @Autowired private TaxonomyService taxonomyService;
    @Autowired private Neo4jClient neo4j;

    @BeforeEach
    void clean() {
        neo4j.query("MATCH (n {t: $tag}) DETACH DELETE n").bind(TAG).to("tag").run();
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    @Test
    void mergingCategoriesMovesSubcategoriesAndMergesSameKeyPairs() {
        // Category and subcategory nameKeys are under live, database-global uniqueness
        // constraints shared with every other test class in this suite run, so every
        // literal here is suffixed with a fresh UUID to guarantee it can never collide
        // with fixture data seeded elsewhere in the shared container.
        String alpha = "txn-alpha-" + UUID.randomUUID();
        String beta = "txn-beta-" + UUID.randomUUID();
        String physics = "txn-physics-" + UUID.randomUUID();
        String biology = "txn-biology-" + UUID.randomUUID();
        neo4j.query("""
                CREATE (catA:Category {t: $tag, id: 'txn-catA', name: $alpha, nameKey: toLower($alpha)})
                CREATE (catB:Category {t: $tag, id: 'txn-catB', name: $beta, nameKey: toLower($beta)})
                CREATE (catA)<-[:SUBCATEGORY_OF]-(:Subcategory {t: $tag, id: 'txn-sub-unique', name: $physics, nameKey: toLower($physics)})
                CREATE (catA)<-[:SUBCATEGORY_OF]-(subDupeA:Subcategory {t: $tag, id: 'txn-sub-dupeA', name: $biology, nameKey: toLower($biology)})
                CREATE (catB)<-[:SUBCATEGORY_OF]-(subDupeB:Subcategory {t: $tag, id: 'txn-sub-dupeB', name: $biology, nameKey: toLower($biology)})
                CREATE (:Tossup {t: $tag, id: 'txn-t1', question: 'q', answer: 'a'})-[:SUBCATEGORY_IS]->(subDupeA)
                """).bind(TAG).to("tag")
                .bind(alpha).to("alpha").bind(beta).to("beta")
                .bind(physics).to("physics").bind(biology).to("biology")
                .run();

        taxonomyService.mergeCategories("txn-catA", "txn-catB");

        // The unique subcategory moved to catB.
        assertThat(categoryIdOf("txn-sub-unique")).isEqualTo("txn-catB");
        // The duplicate pair merged: subDupeA is gone, subDupeB remains under catB,
        // and its tossup followed the merge (D4's "merges same-key pairs recursively").
        assertThat(exists("txn-sub-dupeA")).isFalse();
        assertThat(categoryIdOf("txn-sub-dupeB")).isEqualTo("txn-catB");
        assertThat(tossupSubcategoryId("txn-t1")).isEqualTo("txn-sub-dupeB");
        // The source category itself is gone.
        assertThat(exists("txn-catA")).isFalse();
    }

    @Test
    void mergingSubcategoriesRepointsTossupsAndBonusesAndDeletesSource() {
        String gamma = "txn-gamma-" + UUID.randomUUID();
        String source = "txn-source-" + UUID.randomUUID();
        String target = "txn-target-" + UUID.randomUUID();
        neo4j.query("""
                CREATE (cat:Category {t: $tag, id: 'txn-cat2', name: $gamma, nameKey: toLower($gamma)})
                CREATE (cat)<-[:SUBCATEGORY_OF]-(source:Subcategory {t: $tag, id: 'txn-sub-src', name: $source, nameKey: toLower($source)})
                CREATE (cat)<-[:SUBCATEGORY_OF]-(target:Subcategory {t: $tag, id: 'txn-sub-tgt', name: $target, nameKey: toLower($target)})
                CREATE (:Tossup {t: $tag, id: 'txn-t2', question: 'q', answer: 'a'})-[:SUBCATEGORY_IS]->(source)
                CREATE (:Tossup {t: $tag, id: 'txn-t3', question: 'q', answer: 'a'})-[:SUBCATEGORY_IS]->(source)
                CREATE (source)-[:SUBCATEGORY_IS]->(:Bonus {t: $tag, id: 'txn-b1', preamble: 'p'})
                """).bind(TAG).to("tag")
                .bind(gamma).to("gamma").bind(source).to("source").bind(target).to("target")
                .run();

        taxonomyService.mergeSubcategories("txn-sub-src", "txn-sub-tgt");

        assertThat(tossupSubcategoryId("txn-t2")).isEqualTo("txn-sub-tgt");
        assertThat(tossupSubcategoryId("txn-t3")).isEqualTo("txn-sub-tgt");
        assertThat(bonusSubcategoryId("txn-b1")).isEqualTo("txn-sub-tgt");
        assertThat(exists("txn-sub-src")).isFalse();
    }

    @Test
    void mergingDifficultiesRepointsPackets() {
        // Difficulty nameKeys are also under a live, database-global uniqueness
        // constraint shared with the rest of the suite; generic literals like "Easy"/
        // "Simple" can (and did) collide with unrelated fixture data seeded by other
        // test classes in the same shared container, so these are UUID-suffixed too.
        String easy = "txn-easy-" + UUID.randomUUID();
        String simple = "txn-simple-" + UUID.randomUUID();
        neo4j.query("""
                CREATE (source:Difficulty {t: $tag, id: 'txn-diff-src', name: $easy, nameKey: toLower($easy)})
                CREATE (:Difficulty {t: $tag, id: 'txn-diff-tgt', name: $simple, nameKey: toLower($simple)})
                CREATE (:Packet {t: $tag, id: 'txn-p1', name: 'P1'})-[:DIFFICULTY_LEVEL]->(source)
                CREATE (:Packet {t: $tag, id: 'txn-p2', name: 'P2'})-[:DIFFICULTY_LEVEL]->(source)
                """).bind(TAG).to("tag").bind(easy).to("easy").bind(simple).to("simple").run();

        taxonomyService.mergeDifficulties("txn-diff-src", "txn-diff-tgt");

        assertThat(packetDifficultyId("txn-p1")).isEqualTo("txn-diff-tgt");
        assertThat(packetDifficultyId("txn-p2")).isEqualTo("txn-diff-tgt");
        assertThat(exists("txn-diff-src")).isFalse();
    }

    @Test
    void bankRelationshipsArePreserved() {
        // The bank holds category/subcategory/difficulty as plain string properties, not
        // relationships (M3 Q0 scout finding), so a taxonomy merge has nothing of the
        // bank's to re-point. This pins that a merge leaves a bank node's properties and
        // existence completely untouched. The two authored categories being merged use
        // distinct nameKeys (a live "science" nameKey may already exist and be under the
        // category_namekey uniqueness constraint from a prior TaxonomySchemaInitializer
        // run in this shared container); their names happen to collide with the bank
        // node's plain-string category, which is exactly the case this test is pinning:
        // authored-taxonomy identity is unrelated to bank-string identity.
        neo4j.query("""
                CREATE (:BankTossup {t: $tag, id: 'txn-bank-t1', question: 'q', answer: 'a',
                                      category: 'Science', subcategory: 'Biology', difficulty: 3})
                CREATE (:Category {t: $tag, id: 'txn-bank-catA', name: 'Science', nameKey: 'txn-bank-science'})
                CREATE (:Category {t: $tag, id: 'txn-bank-catB', name: 'Geography', nameKey: 'txn-bank-geography'})
                """).bind(TAG).to("tag").run();

        taxonomyService.mergeCategories("txn-bank-catB", "txn-bank-catA");

        List<Map<String, Object>> rows = neo4j.query("""
                MATCH (b:BankTossup {id: 'txn-bank-t1'}) RETURN b.category AS category,
                       b.subcategory AS subcategory, b.difficulty AS difficulty
                """).fetch().all().stream().toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("category", "Science")
                .containsEntry("subcategory", "Biology")
                .containsEntry("difficulty", 3L);
    }

    private boolean exists(String id) {
        return !neo4j.query("MATCH (n {id: $id}) RETURN n.id").bind(id).to("id").fetch().all().isEmpty();
    }

    private String categoryIdOf(String subcategoryId) {
        return single("MATCH (:Subcategory {id: $id})-[:SUBCATEGORY_OF]->(c:Category) RETURN c.id AS v", subcategoryId);
    }

    private String tossupSubcategoryId(String tossupId) {
        return single("MATCH (:Tossup {id: $id})-[:SUBCATEGORY_IS]->(s:Subcategory) RETURN s.id AS v", tossupId);
    }

    private String bonusSubcategoryId(String bonusId) {
        return single("MATCH (s:Subcategory)-[:SUBCATEGORY_IS]->(:Bonus {id: $id}) RETURN s.id AS v", bonusId);
    }

    private String packetDifficultyId(String packetId) {
        return single("MATCH (:Packet {id: $id})-[:DIFFICULTY_LEVEL]->(d:Difficulty) RETURN d.id AS v", packetId);
    }

    private String single(String cypher, String id) {
        List<Map<String, Object>> rows = neo4j.query(cypher).bind(id).to("id").fetch().all().stream().toList();
        assertThat(rows).as(cypher + " for id=" + id).hasSize(1);
        return (String) rows.get(0).get("v");
    }
}

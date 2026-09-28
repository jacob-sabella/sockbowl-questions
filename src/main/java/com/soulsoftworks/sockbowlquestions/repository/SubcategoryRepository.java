package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubcategoryRepository extends Neo4jRepository<Subcategory, String> {
    Optional<Subcategory> findByName(String name);

    @Query("MATCH (s:Subcategory)-[:SUBCATEGORY_OF]->(c:Category) " +
           "WHERE s.name = $subcategoryName AND c.name = $categoryName " +
           "RETURN s, c")
    Optional<Subcategory> findByNameAndCategoryName(String subcategoryName, String categoryName);

    /**
     * The id of the subcategory under {@code categoryId} with this {@code nameKey}, if
     * any (M3 Q4, D4). Subcategory uniqueness is scoped by category, so two categories
     * may each have a subcategory with the same nameKey.
     */
    @Query("""
            MATCH (s:Subcategory {nameKey: $nameKey})-[:SUBCATEGORY_OF]->(c:Category {id: $categoryId})
            RETURN s.id
            """)
    Optional<String> findIdByNameKeyAndCategoryId(@Param("nameKey") String nameKey, @Param("categoryId") String categoryId);

    /**
     * Idempotent, case-insensitive create scoped to a category (D4): returns the
     * existing subcategory's id when one already has this nameKey under the category,
     * otherwise creates it. Returns null when the category itself does not exist.
     * Duplicate subcategories under one category resolve to the lowest id (Q-M3V1-03).
     */
    @Query("""
            MATCH (cat:Category {id: $categoryId})
            OPTIONAL MATCH (cat)<-[:SUBCATEGORY_OF]-(s0:Subcategory {nameKey: $nameKey})
            WITH cat, s0 ORDER BY s0.id LIMIT 1
            FOREACH (_ IN CASE WHEN s0 IS NULL THEN [1] ELSE [] END |
              MERGE (cat)<-[:SUBCATEGORY_OF]-(sn:Subcategory {nameKey: $nameKey})
                ON CREATE SET sn.id = randomUUID(), sn.name = $name)
            WITH cat
            MATCH (cat)<-[:SUBCATEGORY_OF]-(sub:Subcategory {nameKey: $nameKey})
            RETURN sub.id ORDER BY sub.id LIMIT 1
            """)
    String mergeCreateByNameKey(@Param("categoryId") String categoryId,
                               @Param("nameKey") String nameKey,
                               @Param("name") String name);

    /** Renames the subcategory in place, including its {@code nameKey}. */
    @Query("MATCH (s:Subcategory {id: $id}) SET s.name = $name, s.nameKey = $nameKey")
    void renameById(@Param("id") String id, @Param("name") String name, @Param("nameKey") String nameKey);

    /**
     * Re-points every subcategory under {@code sourceCategoryId} that has no same-key
     * counterpart already under {@code targetCategoryId}, in one write (used by category
     * merge for the "no duplicate" case; duplicate pairs are merged in Java instead, one
     * subcategory pair at a time, so their tossups/bonuses come along).
     */
    @Query("""
            MATCH (s:Subcategory {id: $id})-[r:SUBCATEGORY_OF]->(:Category)
            MATCH (target:Category {id: $targetCategoryId})
            DELETE r
            CREATE (s)-[:SUBCATEGORY_OF]->(target)
            """)
    void repointToCategory(@Param("id") String id, @Param("targetCategoryId") String targetCategoryId);

    /**
     * Merges {@code sourceId} into {@code targetId}: every tossup pointing at the source
     * (OUTGOING) is repointed to the target, every bonus the source points at (OUTGOING
     * from the subcategory) is repointed from the target instead, and the source is then
     * detached and deleted. The caller has already checked both subcategories share a
     * category (cross-category merges are rejected before this runs).
     */
    @Query("""
            MATCH (source:Subcategory {id: $sourceId})
            MATCH (target:Subcategory {id: $targetId})
            OPTIONAL MATCH (t:Tossup)-[:SUBCATEGORY_IS]->(source)
            FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END |
              CREATE (t)-[:SUBCATEGORY_IS]->(target)
            )
            WITH DISTINCT source, target
            OPTIONAL MATCH (source)-[:SUBCATEGORY_IS]->(b:Bonus)
            FOREACH (_ IN CASE WHEN b IS NULL THEN [] ELSE [1] END |
              CREATE (target)-[:SUBCATEGORY_IS]->(b)
            )
            WITH DISTINCT source
            DETACH DELETE source
            """)
    void mergeInto(@Param("sourceId") String sourceId, @Param("targetId") String targetId);

    /**
     * The id and nameKey of every subcategory directly under {@code categoryId} is
     * fetched by {@code TaxonomyService} via {@code Neo4jClient}, not a repository
     * method here: Spring Data Neo4j's repository {@code @Query} only maps a
     * single-column result to a simple return type (confirmed against a real Neo4j: a
     * multi-column {@code List<Map<String,Object>>} throws
     * {@code IllegalArgumentException}), while {@code Neo4jClient.fetch().all()}
     * returns {@code Collection<Map<String,Object>>} natively.
     */

    /** One-time backfill (Q4): fills {@code nameKey} on every subcategory that lacks one. */
    @Query("MATCH (s:Subcategory) WHERE s.nameKey IS NULL SET s.nameKey = toLower(trim(s.name)) RETURN count(s)")
    long backfillNameKeys();
}

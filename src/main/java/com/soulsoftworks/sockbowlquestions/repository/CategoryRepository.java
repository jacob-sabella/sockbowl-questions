package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CategoryRepository extends Neo4jRepository<Category, String> {
    Optional<Category> findByName(String name);

    /** Case-insensitive lookup by {@code nameKey} (M3 Q4, D4). */
    Optional<Category> findByNameKey(String nameKey);

    /**
     * Idempotent, case-insensitive create (D4): returns the existing node's id when one
     * already has this {@code nameKey}, otherwise creates it. The {@code MERGE} takes
     * Neo4j's write lock, so concurrent creates of the same name never race.
     */
    @Query("""
            MERGE (c:Category {nameKey: $nameKey})
              ON CREATE SET c.id = randomUUID(), c.name = $name
            RETURN c.id
            """)
    String mergeCreateByNameKey(@Param("nameKey") String nameKey, @Param("name") String name);

    /**
     * Renames the category in place, including its {@code nameKey}. The caller (
     * {@code TaxonomyService}) checks for a collision first; this write does not.
     */
    @Query("MATCH (c:Category {id: $id}) SET c.name = $name, c.nameKey = $nameKey")
    void renameById(@Param("id") String id, @Param("name") String name, @Param("nameKey") String nameKey);

    /** Deletes the category and every relationship attached to it (used by merge). */
    @Query("MATCH (c:Category {id: $id}) DETACH DELETE c")
    void deleteByIdCascade(@Param("id") String id);

    /** One-time backfill (Q4): fills {@code nameKey} on every category that lacks one. */
    @Query("MATCH (c:Category) WHERE c.nameKey IS NULL SET c.nameKey = toLower(trim(c.name)) RETURN count(c)")
    long backfillNameKeys();

    /**
     * Duplicate-group detection for the startup dedupe check is done with
     * {@code Neo4jClient} in {@code TaxonomySchemaInitializer}, not a repository method:
     * Spring Data Neo4j's repository {@code @Query} only maps a single-column result to
     * a simple return type (confirmed against a real Neo4j: a multi-column
     * {@code List<Map<String,Object>>} throws {@code IllegalArgumentException}, "Records
     * with more than one value cannot be converted without a mapper"), while
     * {@code Neo4jClient.fetch().all()} returns {@code Collection<Map<String,Object>>}
     * natively.
     */

    /** {@code IF NOT EXISTS}, so it is safe to call on every start. */
    @Query("CREATE CONSTRAINT category_namekey IF NOT EXISTS FOR (c:Category) REQUIRE c.nameKey IS UNIQUE")
    void createNameKeyConstraint();
}

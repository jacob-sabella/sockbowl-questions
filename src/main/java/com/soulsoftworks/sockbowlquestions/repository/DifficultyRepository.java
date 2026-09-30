package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DifficultyRepository extends Neo4jRepository<Difficulty, String> {
    Optional<Difficulty> findByName(String name);

    /** Case-insensitive lookup by {@code nameKey} (M3 Q4, D4). */
    Optional<Difficulty> findByNameKey(String nameKey);

    /**
     * Idempotent, case-insensitive create (D4): returns the existing node's id when one
     * already has this {@code nameKey}, otherwise creates it. Legacy case-variant
     * duplicates (no constraint yet) resolve to the lowest id (Q-M3V1-03).
     */
    @Query("""
            OPTIONAL MATCH (d0:Difficulty {nameKey: $nameKey})
            WITH d0 ORDER BY d0.id LIMIT 1
            FOREACH (_ IN CASE WHEN d0 IS NULL THEN [1] ELSE [] END |
              MERGE (dn:Difficulty {nameKey: $nameKey})
                ON CREATE SET dn.id = randomUUID(), dn.name = $name)
            WITH 1 AS resolved
            MATCH (d:Difficulty {nameKey: $nameKey})
            RETURN d.id ORDER BY d.id LIMIT 1
            """)
    String mergeCreateByNameKey(@Param("nameKey") String nameKey, @Param("name") String name);

    /** Renames the difficulty in place, including its {@code nameKey}. */
    @Query("MATCH (d:Difficulty {id: $id}) SET d.name = $name, d.nameKey = $nameKey")
    void renameById(@Param("id") String id, @Param("name") String name, @Param("nameKey") String nameKey);

    /** Sets (or, with an empty string, clears) the difficulty's generation description. */
    @Query("MATCH (d:Difficulty {id: $id}) SET d.description = $description")
    void setDescriptionById(@Param("id") String id, @Param("description") String description);

    /** Seeds a starter description on the difficulty with this nameKey, only if it never had one. */
    @Query("MATCH (d:Difficulty {nameKey: $nameKey}) WHERE d.description IS NULL SET d.description = $description RETURN count(d)")
    long seedDescription(@Param("nameKey") String nameKey, @Param("description") String description);

    /**
     * Merges {@code sourceId} into {@code targetId}: every packet on the source
     * difficulty is repointed to the target, then the source is detached and deleted.
     */
    @Query("""
            MATCH (source:Difficulty {id: $sourceId})
            MATCH (target:Difficulty {id: $targetId})
            OPTIONAL MATCH (p:Packet)-[:DIFFICULTY_LEVEL]->(source)
            FOREACH (_ IN CASE WHEN p IS NULL THEN [] ELSE [1] END |
              CREATE (p)-[:DIFFICULTY_LEVEL]->(target)
            )
            WITH DISTINCT source
            DETACH DELETE source
            """)
    void mergeInto(@Param("sourceId") String sourceId, @Param("targetId") String targetId);

    /** One-time backfill (Q4): fills {@code nameKey} on every difficulty that lacks one. */
    @Query("MATCH (d:Difficulty) WHERE d.nameKey IS NULL SET d.nameKey = toLower(trim(d.name)) RETURN count(d)")
    long backfillNameKeys();

    /**
     * Duplicate-group detection lives in {@code TaxonomySchemaInitializer} via
     * {@code Neo4jClient} instead of a repository method; see the note on
     * {@code CategoryRepository} for why.
     */

    /** {@code IF NOT EXISTS}, so it is safe to call on every start. */
    @Query("CREATE CONSTRAINT difficulty_namekey IF NOT EXISTS FOR (d:Difficulty) REQUIRE d.nameKey IS UNIQUE")
    void createNameKeyConstraint();
}

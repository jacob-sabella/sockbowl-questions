package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Read-only summary of the real dimensions present in the local qbreader question
 * bank ({@code :BankTossup} nodes). The Generate UI uses this to bound its difficulty
 * and year controls to values that actually exist, rather than guessing.
 *
 * <p>Each query returns a single-column {@code row} holding the whole collection, so
 * the result is a one-element {@code List} (empty when the bank has no matching data).
 */
@Repository
public interface BankDimensionsRepository extends Neo4jRepository<Packet, String> {

    /** Distinct BankTossup.difficulty values present, ascending. */
    @Query("""
            MATCH (t:BankTossup) WHERE t.difficulty IS NOT NULL
            WITH DISTINCT t.difficulty AS d ORDER BY d
            RETURN collect(d) AS row
            """)
    List<Integer> distinctDifficulties();

    /** Distinct BankTossup.year values present (ignoring null/0), ascending. */
    @Query("""
            MATCH (t:BankTossup) WHERE t.year IS NOT NULL AND t.year > 0
            WITH DISTINCT t.year AS y ORDER BY y
            RETURN collect(y) AS row
            """)
    List<Integer> distinctYears();
}

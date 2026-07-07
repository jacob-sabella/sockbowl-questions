package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Read-only aggregate stats over the local qbreader question bank ({@code :BankTossup} /
 * {@code :BankBonus}) plus {@code :QuestionSet} nodes. Powers the {@code /api/qbreader/stats}
 * dashboard endpoint: total counts, the tossup year range, and a per-difficulty histogram.
 *
 * <p>Each query RETURNs exactly one row so the caller reads element 0; the year and
 * difficulty queries tolerate an empty bank (null / empty map) without error.
 */
@Repository
public interface BankStatsRepository extends Neo4jRepository<Packet, String> {

    /** Total number of {@code :BankTossup} nodes. */
    @Query("MATCH (t:BankTossup) RETURN count(t)")
    long countTossups();

    /** Total number of {@code :BankBonus} nodes. */
    @Query("MATCH (b:BankBonus) RETURN count(b)")
    long countBonuses();

    /** Total number of {@code :QuestionSet} nodes. */
    @Query("MATCH (s:QuestionSet) RETURN count(s)")
    long countSets();

    /**
     * Minimum and maximum {@code BankTossup.year}, ignoring null/0 sentinels, as one
     * {minYear, maxYear} map. Returns an empty map (or nulls) when no years are present.
     */
    @Query("""
            MATCH (t:BankTossup)
            WHERE t.year IS NOT NULL AND t.year > 0
            RETURN {minYear: min(t.year), maxYear: max(t.year)} AS row
            """)
    List<Map<String, Object>> yearRange();

    /** Bank tossup count per difficulty, as one {difficulty: count} map. */
    @Query("""
            MATCH (t:BankTossup) WHERE t.difficulty IS NOT NULL
            WITH toString(t.difficulty) AS d, count(t) AS n
            RETURN apoc.map.fromPairs(collect([d, n])) AS row
            """)
    List<Map<String, Object>> difficultyCounts();
}

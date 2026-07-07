package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Read-only sampling over the local qbreader question bank ({@code :BankTossup} /
 * {@code :BankBonus} nodes loaded from the qbreader dump). Filters are STRICT and
 * complete — every matching question is a candidate before the random {@code LIMIT},
 * so e.g. an alternate-subcategory of "Computer Science" returns questions actually
 * tagged Computer Science rather than a leaky remote sample.
 *
 * <p>A null list parameter means "no constraint" on that dimension; category/subcategory
 * are coalesced to non-null on return so the downstream {@code batchCreatePacket} MERGE
 * never hits a null taxonomy name.
 */
@Repository
public interface BankRepository extends Neo4jRepository<Packet, String> {

    @Query("""
            MATCH (t:BankTossup)
            WHERE ($categories       IS NULL OR t.category       IN $categories)
              AND ($subcategories    IS NULL OR t.subcategory    IN $subcategories)
              AND ($altSubcategories IS NULL OR t.altSubcategory IN $altSubcategories)
              AND ($difficulties     IS NULL OR t.difficulty     IN $difficulties)
              AND ($minYear IS NULL OR t.year >= $minYear)
              AND ($maxYear IS NULL OR t.year <= $maxYear)
              AND ($standardOnly = false OR t.standard = true)
              AND NOT t.remoteId IN $excludeRemoteIds
            WITH t, rand() AS r ORDER BY r LIMIT $count
            RETURN t.remoteId AS remoteId, t.question AS question, t.answer AS answer,
                   coalesce(t.category, 'Miscellaneous') AS category,
                   coalesce(t.subcategory, t.category, 'Miscellaneous') AS subcategory
            """)
    List<Map<String, Object>> sampleBankTossups(
            @Param("categories") List<String> categories,
            @Param("subcategories") List<String> subcategories,
            @Param("altSubcategories") List<String> altSubcategories,
            @Param("difficulties") List<Integer> difficulties,
            @Param("minYear") Integer minYear,
            @Param("maxYear") Integer maxYear,
            @Param("standardOnly") boolean standardOnly,
            @Param("excludeRemoteIds") List<String> excludeRemoteIds,
            @Param("count") int count);

    @Query("""
            MATCH (b:BankBonus)
            WHERE ($categories       IS NULL OR b.category       IN $categories)
              AND ($subcategories    IS NULL OR b.subcategory    IN $subcategories)
              AND ($altSubcategories IS NULL OR b.altSubcategory IN $altSubcategories)
              AND ($difficulties     IS NULL OR b.difficulty     IN $difficulties)
              AND ($minYear IS NULL OR b.year >= $minYear)
              AND ($maxYear IS NULL OR b.year <= $maxYear)
              AND ($standardOnly = false OR b.standard = true)
              AND NOT b.remoteId IN $excludeRemoteIds
            WITH b, rand() AS r ORDER BY r LIMIT $count
            OPTIONAL MATCH (b)-[hp:HAS_PART]->(bp:BankBonusPart)
            WITH b, bp, hp ORDER BY hp.order
            WITH b, collect(CASE WHEN bp IS NULL THEN null
                                 ELSE {question: bp.question, answer: bp.answer, order: hp.order} END) AS parts
            RETURN b.remoteId AS remoteId, b.preamble AS preamble,
                   coalesce(b.category, 'Miscellaneous') AS category,
                   coalesce(b.subcategory, b.category, 'Miscellaneous') AS subcategory,
                   [p IN parts WHERE p IS NOT NULL] AS parts
            """)
    List<Map<String, Object>> sampleBankBonuses(
            @Param("categories") List<String> categories,
            @Param("subcategories") List<String> subcategories,
            @Param("altSubcategories") List<String> altSubcategories,
            @Param("difficulties") List<Integer> difficulties,
            @Param("minYear") Integer minYear,
            @Param("maxYear") Integer maxYear,
            @Param("standardOnly") boolean standardOnly,
            @Param("excludeRemoteIds") List<String> excludeRemoteIds,
            @Param("count") int count);
}

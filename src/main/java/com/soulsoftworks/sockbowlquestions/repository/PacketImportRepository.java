package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Write path for plaintext import/clone (D5, plan 3.1.8). Deliberately separate from
 * {@link PacketRepository}, and not a {@code @GraphQlRepository} (that annotation for the
 * {@code Packet} type already lives on {@link PacketRepository}): the one thing that sets
 * this repository apart is that it never creates taxonomy. {@link #createImportedPacket}
 * only ever attaches a difficulty or subcategory it finds by id, via {@code OPTIONAL MATCH}
 * plus a {@code FOREACH} guard — never {@code MERGE} — so an import or clone can't grow the
 * taxonomy tree (D4).
 *
 * <p>Extends {@code Neo4jRepository<Subcategory, String>} (not {@code Packet}), purely so
 * {@link #findTaxonomyByKeys} gets full entity hydration (including the {@code category}
 * relationship) for its returned node — Spring Data Neo4j only applies that to a query
 * method whose return type matches the repository's own declared entity. Nothing here
 * calls the inherited CRUD methods on either entity; {@link #createImportedPacket} returns
 * a plain id, not an entity, so the base type otherwise has no effect.
 */
@Repository
public interface PacketImportRepository extends Neo4jRepository<Subcategory, String> {

    /**
     * Resolves a plaintext category tag ({@link com.soulsoftworks.sockbowlquestions.packetio.CategoryTag#split})
     * against existing taxonomy only, case-insensitively (plan 3.1.8). Before Q4's
     * {@code nameKey} field lands, this matches on {@code toLower(name)} directly, exactly
     * as the plan allows ("before Q4 lands, fall back to toLower(name) matching").
     *
     * <p>Two independent branches, coalesced:
     * <ul>
     *   <li>{@code subcategoryName} given: a subcategory with that name under a category with
     *       {@code categoryName};</li>
     *   <li>{@code subcategoryName} null (a category-only tag): a subcategory whose name
     *       equals {@code categoryName}, under a category with the same name — "a
     *       category-only match resolves to a subcategory with the same name under that
     *       category if one exists".</li>
     * </ul>
     * Returns empty when neither branch matches (the caller records an
     * {@code UNKNOWN_CATEGORY_TAG} warning and leaves the item uncategorized).
     */
    @Query("""
            OPTIONAL MATCH (subExact:Subcategory)-[:SUBCATEGORY_OF]->(catExact:Category)
              WHERE $subcategoryName IS NOT NULL
                AND toLower(subExact.name) = toLower($subcategoryName)
                AND toLower(catExact.name) = toLower($categoryName)
            OPTIONAL MATCH (subSame:Subcategory)-[:SUBCATEGORY_OF]->(catSame:Category)
              WHERE $subcategoryName IS NULL
                AND toLower(subSame.name) = toLower($categoryName)
                AND toLower(catSame.name) = toLower($categoryName)
            WITH coalesce(subExact, subSame) AS sub
            WHERE sub IS NOT NULL
            MATCH (sub)-[rel:SUBCATEGORY_OF]->(cat:Category)
            RETURN sub, rel, cat
            LIMIT 1
            """)
    Optional<Subcategory> findTaxonomyByKeys(@Param("categoryName") String categoryName,
                                             @Param("subcategoryName") String subcategoryName);

    /**
     * Creates a whole packet from already-resolved rows — never MERGE, only
     * {@code OPTIONAL MATCH} by id plus a {@code FOREACH} guard for the optional
     * difficulty/subcategory links, so import and clone can never create taxonomy
     * (D4). The packet is always DRAFT with {@code version = 0} (plan 3.1.8): imported
     * and cloned content starts private, and clone/export authorization is checked
     * beforehand in {@code PacketImportService}, not here.
     *
     * @param tossups list of maps: question, answer, subcategoryId (nullable), order
     * @param bonuses list of maps: preamble, subcategoryId (nullable), order, parts
     *                (each part: question, answer, order)
     * @param createdVia node-only provenance (e.g. {@code "TEXT_IMPORT"}, {@code "CLONED"});
     *                    not mapped on {@link Packet} (M4 owns the mapped provenance fields)
     * @param source     the {@link com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource}
     *                    name (D13, M4-PV-01, INT1) to stamp on the packet and every node it creates
     *                    ({@code TEXT_IMPORT} for {@code importPacket}, {@code CLONED} for
     *                    {@code clonePacket}); {@code aiModel} is intentionally left unset (null).
     * @param auditor    the resolved auditor value (D13, M4-PV-01, INT1) to stamp as
     *                    {@code createdBy}/{@code lastModifiedBy} on every node this creates
     *                    (see {@link com.soulsoftworks.sockbowlquestions.security.SecurityAuditorAware}).
     *                    This raw-Cypher path bypasses SDN's save pipeline (no
     *                    {@code @EnableNeo4jAuditing} callback runs), so the caller resolves and
     *                    passes it explicitly, the same as {@link PacketRepository#batchCreatePacket}.
     * @param nowIso     an ISO-8601 instant (as produced by {@code Instant.now().toString()}) to
     *                    stamp as {@code createdAt}/{@code lastModifiedAt}, parsed with Cypher's
     *                    {@code datetime()} so it's stored as a native temporal value.
     * @return the new packet's id
     */
    @Query("""
            OPTIONAL MATCH (d:Difficulty {id: $difficultyId})
            CREATE (p:Packet {id: randomUUID(), name: $packetName, ownerId: $ownerId,
                              ownerDisplayName: $ownerDisplayName, visibility: $visibility,
                              version: 0, createdVia: $createdVia, source: $source,
                              createdBy: $auditor, createdAt: datetime($nowIso),
                              lastModifiedBy: $auditor, lastModifiedAt: datetime($nowIso)})
            FOREACH (_ IN CASE WHEN d IS NULL THEN [] ELSE [1] END |
              CREATE (p)-[:DIFFICULTY_LEVEL]->(d)
            )
            WITH p
            CALL (p) {
              UNWIND $tossups AS t
                OPTIONAL MATCH (sub:Subcategory {id: t.subcategoryId})
                CREATE (tu:Tossup {id: randomUUID(), question: t.question, answer: t.answer,
                                   source: $source, createdBy: $auditor, createdAt: datetime($nowIso),
                                   lastModifiedBy: $auditor, lastModifiedAt: datetime($nowIso)})
                FOREACH (_ IN CASE WHEN sub IS NULL THEN [] ELSE [1] END |
                  CREATE (tu)-[:SUBCATEGORY_IS]->(sub)
                )
                CREATE (p)-[:CONTAINS_TOSSUP {order: t.order}]->(tu)
            }
            WITH p
            CALL (p) {
              UNWIND $bonuses AS b
                OPTIONAL MATCH (sub:Subcategory {id: b.subcategoryId})
                CREATE (bo:Bonus {id: randomUUID(), preamble: b.preamble,
                                  source: $source, createdBy: $auditor, createdAt: datetime($nowIso),
                                  lastModifiedBy: $auditor, lastModifiedAt: datetime($nowIso)})
                FOREACH (_ IN CASE WHEN sub IS NULL THEN [] ELSE [1] END |
                  CREATE (sub)-[:SUBCATEGORY_IS]->(bo)
                )
                CREATE (p)-[:CONTAINS_BONUS {order: b.order}]->(bo)
                WITH bo, b
                UNWIND b.parts AS part
                  CREATE (bp:BonusPart {id: randomUUID(), question: part.question, answer: part.answer,
                                        source: $source, createdBy: $auditor, createdAt: datetime($nowIso),
                                        lastModifiedBy: $auditor, lastModifiedAt: datetime($nowIso)})
                  CREATE (bo)-[:HAS_PART {order: part.order}]->(bp)
            }
            RETURN p.id AS id
            """)
    String createImportedPacket(@Param("packetName") String packetName,
                                @Param("difficultyId") String difficultyId,
                                @Param("tossups") List<Map<String, Object>> tossups,
                                @Param("bonuses") List<Map<String, Object>> bonuses,
                                @Param("ownerId") String ownerId,
                                @Param("ownerDisplayName") String ownerDisplayName,
                                @Param("visibility") String visibility,
                                @Param("createdVia") String createdVia,
                                @Param("source") String source,
                                @Param("auditor") String auditor,
                                @Param("nowIso") String nowIso);
}

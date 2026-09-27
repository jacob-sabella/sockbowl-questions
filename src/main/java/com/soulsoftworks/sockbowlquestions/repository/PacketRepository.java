package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.graphql.data.GraphQlRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@GraphQlRepository
public interface PacketRepository extends Neo4jRepository<Packet, String> {
    Packet getPacketById(String id);

    /**
     * Unfiltered name search. Internal use only (e.g. unique-name checks); caller-facing
     * reads go through {@link #searchVisibleByName} and {@code PacketReadPolicy}.
     */
    @Query("MATCH (p:Packet) WHERE toLower(p.name) CONTAINS toLower($name) RETURN p")
    List<Packet> searchByName(String name);

    /**
     * Ids of every listed packet, for callers who may read every packet in full: all
     * packets except those whose effective visibility is unlisted (game-only EPHEMERAL
     * packets, D15). Returns ids so the caller can load full packets with {@code findAllById}.
     *
     * @param unlistedVisibilities names of the {@code PacketVisibility} values never listed
     * @param legacyVisibility     the visibility a node without one counts as
     */
    @Query("""
            MATCH (p:Packet)
            WHERE NOT coalesce(p.visibility, $legacyVisibility) IN $unlistedVisibilities
            RETURN p.id
            """)
    List<String> findListedPacketIds(@Param("unlistedVisibilities") List<String> unlistedVisibilities,
                                     @Param("legacyVisibility") String legacyVisibility);

    /** {@link #searchByName} without the unlisted (game-only) packets; see {@link #findListedPacketIds}. */
    @Query("""
            MATCH (p:Packet)
            WHERE toLower(p.name) CONTAINS toLower($name)
              AND NOT coalesce(p.visibility, $legacyVisibility) IN $unlistedVisibilities
            RETURN p
            """)
    List<Packet> searchListedByName(@Param("name") String name,
                                    @Param("unlistedVisibilities") List<String> unlistedVisibilities,
                                    @Param("legacyVisibility") String legacyVisibility);

    /**
     * Ids of the packets a caller without full-read rights may see (D2): those whose
     * effective visibility is publicly readable, plus the caller's own packets.
     * Returns ids so the caller can load full packets with {@code findAllById}.
     *
     * @param publicVisibilities names of the publicly readable {@code PacketVisibility} values
     * @param legacyVisibility   the visibility a node without one counts as
     * @param ownerId            the caller's {@code sub}, or null for anonymous callers
     */
    @Query("""
            MATCH (p:Packet)
            WHERE coalesce(p.visibility, $legacyVisibility) IN $publicVisibilities
               OR ($ownerId IS NOT NULL AND p.ownerId = $ownerId)
            RETURN p.id
            """)
    List<String> findVisiblePacketIds(@Param("publicVisibilities") List<String> publicVisibilities,
                                      @Param("legacyVisibility") String legacyVisibility,
                                      @Param("ownerId") String ownerId);

    /** {@link #searchByName} restricted to what {@link #findVisiblePacketIds} would return. */
    @Query("""
            MATCH (p:Packet)
            WHERE toLower(p.name) CONTAINS toLower($name)
              AND (coalesce(p.visibility, $legacyVisibility) IN $publicVisibilities
                   OR ($ownerId IS NOT NULL AND p.ownerId = $ownerId))
            RETURN p
            """)
    List<Packet> searchVisibleByName(@Param("name") String name,
                                     @Param("publicVisibilities") List<String> publicVisibilities,
                                     @Param("legacyVisibility") String legacyVisibility,
                                     @Param("ownerId") String ownerId);

    /**
     * One-time backfill for D2: legacy packets without a visibility become PUBLISHED so
     * existing bank packets stay usable by guests. Idempotent (only touches nulls).
     *
     * @return how many packets were updated
     */
    @Query("MATCH (p:Packet) WHERE p.visibility IS NULL SET p.visibility = $visibility RETURN count(p)")
    long backfillMissingVisibility(@Param("visibility") String visibility);

    /**
     * One-time D13 backfill (M4-PV-01): legacy packets that predate provenance tracking
     * get {@code createdBy}/{@code lastModifiedBy} backfilled from their {@code ownerId}
     * (the only historical signal available), and {@code createdAt}/{@code lastModifiedAt}
     * backfilled to "now" if unset. Ownerless legacy packets are left with no
     * {@code createdBy}: there is no signal to attribute them to, and this never touches
     * {@code ownerId} itself. Idempotent: only matches packets with no {@code createdBy}
     * yet, so a packet already backfilled (or created after M4 shipped, which always
     * stamps createdBy via {@code @EnableNeo4jAuditing} or the raw-Cypher paths) is never
     * matched again.
     *
     * @return how many packets were updated
     */
    @Query("""
            MATCH (p:Packet)
            WHERE p.createdBy IS NULL AND p.ownerId IS NOT NULL
            SET p.createdBy = p.ownerId,
                p.lastModifiedBy = coalesce(p.lastModifiedBy, p.ownerId),
                p.createdAt = coalesce(p.createdAt, datetime()),
                p.lastModifiedAt = coalesce(p.lastModifiedAt, datetime())
            RETURN count(p)
            """)
    long backfillProvenanceFromOwner();

    /** Resolves the owning packet of a tossup, for ownership checks on node-level mutations. */
    @Query("MATCH (p:Packet)-[:CONTAINS_TOSSUP]->(t:Tossup {id: $tossupId}) RETURN p LIMIT 1")
    Optional<Packet> findByTossupId(@Param("tossupId") String tossupId);

    /** Resolves the owning packet of a bonus, for ownership checks on node-level mutations. */
    @Query("MATCH (p:Packet)-[:CONTAINS_BONUS]->(b:Bonus {id: $bonusId}) RETURN p LIMIT 1")
    Optional<Packet> findByBonusId(@Param("bonusId") String bonusId);

    /**
     * Resolves the owning packet of a bonus part (via its bonus), for the D13
     * provenance field gate ({@code ProvenanceFieldResolver}, M4-PV-01).
     */
    @Query("MATCH (p:Packet)-[:CONTAINS_BONUS]->(:Bonus)-[:HAS_PART]->(bp:BonusPart {id: $bonusPartId}) RETURN p LIMIT 1")
    Optional<Packet> findByBonusPartId(@Param("bonusPartId") String bonusPartId);

    /**
     * Creates a whole packet — difficulty, tossups, bonuses, bonus parts, and
     * the taxonomy each references — in a single write, instead of ~40
     * sequential authoring calls. Categories/Subcategories/Difficulty are
     * MERGE-d by name (subcategory scoped to its category) so the taxonomy is
     * reused, not duplicated; new question nodes get fresh UUID ids.
     *
     * @param tossups list of maps: question, answer, category, subcategory, order
     * @param bonuses list of maps: preamble, category, subcategory, order, parts
     *                (each part: question, answer, order)
     * @param visibility name of the new packet's {@code PacketVisibility}; an EPHEMERAL
     *                   packet (D15) is also stamped with {@code ephemeralCreatedAt = datetime()}
     *                   for the TTL cleanup ({@link #findExpiredEphemeralPacketIds})
     * @param createdVia how the packet was made (e.g. {@code "import-random"}); stored as-is.
     *                   Node-only provenance, not mapped on {@link Packet} (M4 owns the
     *                   mapped provenance fields)
     * @param auditor    the resolved auditor value (D13, M4-PV-01) to stamp as {@code createdBy}
     *                   and {@code lastModifiedBy} on every node this creates; see
     *                   {@link com.soulsoftworks.sockbowlquestions.security.SecurityAuditorAware}.
     *                   This raw-Cypher path bypasses SDN's save pipeline (no {@code @EnableNeo4jAuditing}
     *                   callback runs), so the caller resolves and passes it explicitly.
     * @param nowIso     an ISO-8601 instant (as produced by {@code Instant.now().toString()}) to
     *                   stamp as {@code createdAt}/{@code lastModifiedAt}, parsed with Cypher's
     *                   {@code datetime()} so it's stored as a native temporal value like every
     *                   SDN-managed write.
     * @param source     the {@link com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource}
     *                   name to stamp on every node this creates (e.g. {@code QBREADER_IMPORT}).
     *                   {@code aiModel} is intentionally left unset (null) on this path.
     * @return the new packet's id
     */
    @Query("""
            MERGE (d:Difficulty {name: $difficultyName})
              ON CREATE SET d.id = randomUUID()
            CREATE (p:Packet {id: randomUUID(), name: $packetName, ownerId: $ownerId, ownerDisplayName: $ownerDisplayName,
                              visibility: $visibility, createdVia: $createdVia,
                              ephemeralCreatedAt: CASE WHEN $visibility = 'EPHEMERAL' THEN datetime() ELSE null END,
                              source: $source, createdBy: $auditor, createdAt: datetime($nowIso),
                              lastModifiedBy: $auditor, lastModifiedAt: datetime($nowIso)})
            CREATE (p)-[:DIFFICULTY_LEVEL]->(d)
            WITH p
            CALL (p) {
              UNWIND $tossups AS t
                MERGE (cat:Category {name: t.category})
                  ON CREATE SET cat.id = randomUUID()
                MERGE (cat)<-[:SUBCATEGORY_OF]-(sub:Subcategory {name: t.subcategory})
                  ON CREATE SET sub.id = randomUUID()
                CREATE (tu:Tossup {id: randomUUID(), question: t.question, answer: t.answer, remoteId: t.remoteId,
                                   source: $source, createdBy: $auditor, createdAt: datetime($nowIso),
                                   lastModifiedBy: $auditor, lastModifiedAt: datetime($nowIso)})
                CREATE (tu)-[:SUBCATEGORY_IS]->(sub)
                CREATE (p)-[:CONTAINS_TOSSUP {order: t.order}]->(tu)
            }
            WITH p
            CALL (p) {
              UNWIND $bonuses AS b
                MERGE (cat:Category {name: b.category})
                  ON CREATE SET cat.id = randomUUID()
                MERGE (cat)<-[:SUBCATEGORY_OF]-(sub:Subcategory {name: b.subcategory})
                  ON CREATE SET sub.id = randomUUID()
                CREATE (bo:Bonus {id: randomUUID(), preamble: b.preamble, remoteId: b.remoteId,
                                  source: $source, createdBy: $auditor, createdAt: datetime($nowIso),
                                  lastModifiedBy: $auditor, lastModifiedAt: datetime($nowIso)})
                CREATE (sub)-[:SUBCATEGORY_IS]->(bo)
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
    String batchCreatePacket(@Param("packetName") String packetName,
                             @Param("difficultyName") String difficultyName,
                             @Param("tossups") List<Map<String, Object>> tossups,
                             @Param("bonuses") List<Map<String, Object>> bonuses,
                             @Param("ownerId") String ownerId,
                             @Param("ownerDisplayName") String ownerDisplayName,
                             @Param("visibility") String visibility,
                             @Param("createdVia") String createdVia,
                             @Param("auditor") String auditor,
                             @Param("nowIso") String nowIso,
                             @Param("source") String source);

    /**
     * Ids of EPHEMERAL packets (D15) created before {@code cutoffEpochMillis}, oldest
     * first, at most {@code limit}. An EPHEMERAL packet without a creation stamp counts
     * as expired (only {@link #batchCreatePacket} creates them, and it always stamps).
     */
    @Query("""
            MATCH (p:Packet {visibility: 'EPHEMERAL'})
            WHERE p.ephemeralCreatedAt IS NULL
               OR p.ephemeralCreatedAt < datetime({epochMillis: $cutoffEpochMillis})
            RETURN p.id
            ORDER BY p.ephemeralCreatedAt
            LIMIT $limit
            """)
    List<String> findExpiredEphemeralPacketIds(@Param("cutoffEpochMillis") long cutoffEpochMillis,
                                               @Param("limit") int limit);

    /**
     * Delete a packet and the question nodes it owns. Every packet's tossups/bonuses/
     * bonus-parts are created fresh and owned by exactly that packet (both authored and
     * generated packets copy their questions), so cascading avoids orphan build-up. The
     * shared taxonomy (Category/Subcategory/Difficulty) is intentionally left intact.
     */
    @Query("""
            MATCH (p:Packet {id: $id})
            OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t:Tossup)
            OPTIONAL MATCH (p)-[:CONTAINS_BONUS]->(b:Bonus)
            OPTIONAL MATCH (b)-[:HAS_PART]->(bp:BonusPart)
            DETACH DELETE p, t, b, bp
            """)
    void deletePacketCascade(@Param("id") String id);
}

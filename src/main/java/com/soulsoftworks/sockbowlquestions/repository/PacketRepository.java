package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deliberately not a {@code @GraphQlRepository}: Spring for GraphQL would auto-register
 * it (it is a {@code QueryByExampleExecutor}) as the data fetcher for any Packet-returning
 * query without an explicit controller mapping, bypassing {@code PacketReadPolicy} and the
 * answer-free projection. Every caller-facing read goes through {@code GraphQLController}
 * ({@code NoAutoRegisteredGraphQlRepositoryTest} guards this).
 */
@Repository
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

    /* ---------------- Optimistic locking (M3 Q2, PB-18, plan 3.1.4) ---------------- */

    /**
     * Takes the packet's write lock, checks the version and bumps it, in one statement.
     * Every content mutation in {@code PacketAuthoringService} calls this first, so
     * concurrent mutations of one packet are serialized for the rest of the transaction.
     *
     * <p>The {@code SET p.versionLock ... REMOVE} pair exists only to acquire the node's
     * exclusive lock <em>before</em> {@code p.version} is read. Neo4j reads are
     * read-committed without locks, so reading the version before locking would let two
     * transactions both see version N and both write N+1 (a lost update). Once the lock is
     * held, the read sees the latest committed version.
     *
     * <p>Also stamps {@code lastModifiedBy}/{@code lastModifiedAt} (D13, M4-PV-01, INT1)
     * on a successful bump. This is the only touch a node-level content mutation
     * (a tossup/bonus/bonus-part add, update, remove or reorder) makes to the
     * <em>packet</em> itself: those mutations save the child node through SDN (which
     * stamps the child's own audit fields), but never re-save the packet, so without
     * this the packet's {@code lastModifiedBy}/{@code lastModifiedAt} would go stale
     * on every content edit that isn't a direct packet-level mutation.
     *
     * @param id       the packet to bump
     * @param expected the version the caller last saw, or null to skip the check
     * @param auditor  the resolved auditor value (see
     *                 {@link com.soulsoftworks.sockbowlquestions.security.SecurityAuditorAware}) to
     *                 stamp as {@code lastModifiedBy}
     * @param nowIso   an ISO-8601 instant ({@code Instant.now().toString()}) to stamp as
     *                 {@code lastModifiedAt}, parsed with Cypher's {@code datetime()}
     * @return the new version, or null when the packet doesn't exist or {@code expected}
     *         doesn't match (tell the two apart with {@link #currentVersion})
     */
    @Query("""
            MATCH (p:Packet {id: $id})
            SET p.versionLock = true
            REMOVE p.versionLock
            WITH p
            WHERE $expected IS NULL OR coalesce(p.version, 0) = $expected
            SET p.version = coalesce(p.version, 0) + 1,
                p.lastModifiedBy = $auditor,
                p.lastModifiedAt = datetime($nowIso)
            RETURN p.version
            """)
    Long bumpVersion(@Param("id") String id, @Param("expected") Long expected,
                     @Param("auditor") String auditor, @Param("nowIso") String nowIso);

    /** The packet's stored version (null reads as 0); empty when the packet doesn't exist. */
    @Query("MATCH (p:Packet {id: $id}) RETURN coalesce(p.version, 0)")
    Optional<Long> currentVersion(@Param("id") String id);

    /** Id of the packet containing the tossup, for the version bump of node-level mutations. */
    @Query("MATCH (p:Packet)-[:CONTAINS_TOSSUP]->(:Tossup {id: $tossupId}) RETURN p.id LIMIT 1")
    Optional<String> findPacketIdByTossupId(@Param("tossupId") String tossupId);

    /** Id of the packet containing the bonus, for the version bump of node-level mutations. */
    @Query("MATCH (p:Packet)-[:CONTAINS_BONUS]->(:Bonus {id: $bonusId}) RETURN p.id LIMIT 1")
    Optional<String> findPacketIdByBonusId(@Param("bonusId") String bonusId);

    /**
     * Body of the per-row {@code CALL (t)} in {@link #batchCreatePacket}: resolves
     * {@code t.category}/{@code t.subcategory} to exactly one {@code cat} and one {@code sub}
     * (lowest id among case-variant duplicates), creating each only when absent (Q-M3V1-03).
     */
    String RESOLVE_TAXONOMY_ROW = """
                  OPTIONAL MATCH (c0:Category {nameKey: toLower(trim(t.category))})
                  WITH t, c0 ORDER BY c0.id LIMIT 1
                  FOREACH (_ IN CASE WHEN c0 IS NULL THEN [1] ELSE [] END |
                    MERGE (cn:Category {nameKey: toLower(trim(t.category))})
                      ON CREATE SET cn.id = randomUUID(), cn.name = t.category)
                  WITH t
                  CALL (t) {
                    MATCH (c:Category {nameKey: toLower(trim(t.category))})
                    RETURN c AS cat ORDER BY c.id LIMIT 1
                  }
                  OPTIONAL MATCH (cat)<-[:SUBCATEGORY_OF]-(s0:Subcategory {nameKey: toLower(trim(t.subcategory))})
                  WITH t, cat, s0 ORDER BY s0.id LIMIT 1
                  FOREACH (_ IN CASE WHEN s0 IS NULL THEN [1] ELSE [] END |
                    MERGE (cat)<-[:SUBCATEGORY_OF]-(sn:Subcategory {nameKey: toLower(trim(t.subcategory))})
                      ON CREATE SET sn.id = randomUUID(), sn.name = t.subcategory)
                  WITH t, cat
                  CALL (cat, t) {
                    MATCH (cat)<-[:SUBCATEGORY_OF]-(s:Subcategory {nameKey: toLower(trim(t.subcategory))})
                    RETURN s AS sub ORDER BY s.id LIMIT 1
                  }
                  RETURN cat, sub
            """;

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
     * resolved by {@code nameKey} (subcategory scoped to its category, M3 Q4, D4) so the
     * taxonomy is reused, not duplicated, and follows the same dedupe rule as
     * {@code TaxonomyService}'s create methods; new question nodes get fresh UUID ids.
     *
     * <p>Resolution never fans out (Q-M3V1-03): when legacy case-variant duplicates share a
     * {@code nameKey} (the initializer leaves them, and skips the constraint, unless
     * dedupe-on-startup is on), the lowest-id node is used, and a node is only created
     * (via {@code MERGE}, so concurrent creators still serialize) when none exists. Each
     * row resolves in its own {@code CALL (t)} execution, so a name created by one row is
     * seen by the next.
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
            CALL () {
              OPTIONAL MATCH (d0:Difficulty {nameKey: toLower(trim($difficultyName))})
              WITH d0 ORDER BY d0.id LIMIT 1
              FOREACH (_ IN CASE WHEN d0 IS NULL THEN [1] ELSE [] END |
                MERGE (dn:Difficulty {nameKey: toLower(trim($difficultyName))})
                  ON CREATE SET dn.id = randomUUID(), dn.name = $difficultyName)
            }
            CALL () {
              MATCH (d:Difficulty {nameKey: toLower(trim($difficultyName))})
              RETURN d ORDER BY d.id LIMIT 1
            }
            CREATE (p:Packet {id: randomUUID(), name: $packetName, ownerId: $ownerId, ownerDisplayName: $ownerDisplayName,
                              visibility: $visibility, createdVia: $createdVia,
                              ephemeralCreatedAt: CASE WHEN $visibility = 'EPHEMERAL' THEN datetime() ELSE null END,
                              source: $source, createdBy: $auditor, createdAt: datetime($nowIso),
                              lastModifiedBy: $auditor, lastModifiedAt: datetime($nowIso)})
            CREATE (p)-[:DIFFICULTY_LEVEL]->(d)
            WITH p
            CALL (p) {
              UNWIND $tossups AS t
                CALL (t) {
            """ + RESOLVE_TAXONOMY_ROW + """
                }
                CREATE (tu:Tossup {id: randomUUID(), question: t.question, answer: t.answer, remoteId: t.remoteId,
                                   source: $source, createdBy: $auditor, createdAt: datetime($nowIso),
                                   lastModifiedBy: $auditor, lastModifiedAt: datetime($nowIso)})
                CREATE (tu)-[:SUBCATEGORY_IS]->(sub)
                CREATE (p)-[:CONTAINS_TOSSUP {order: t.order}]->(tu)
            }
            WITH p
            CALL (p) {
              UNWIND $bonuses AS t
                CALL (t) {
            """ + RESOLVE_TAXONOMY_ROW + """
                }
                CREATE (bo:Bonus {id: randomUUID(), preamble: t.preamble, remoteId: t.remoteId,
                                  source: $source, createdBy: $auditor, createdAt: datetime($nowIso),
                                  lastModifiedBy: $auditor, lastModifiedAt: datetime($nowIso)})
                CREATE (sub)-[:SUBCATEGORY_IS]->(bo)
                CREATE (p)-[:CONTAINS_BONUS {order: t.order}]->(bo)
                WITH bo, t
                UNWIND t.parts AS part
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

    /**
     * Packets owned by a subject (the {@code packets-owned} quota, D10, WP-Q4). An
     * EPHEMERAL packet has no owner, so it never counts.
     */
    @Query("MATCH (p:Packet) WHERE p.ownerId = $ownerId RETURN count(p)")
    long countByOwnerId(@Param("ownerId") String ownerId);
}

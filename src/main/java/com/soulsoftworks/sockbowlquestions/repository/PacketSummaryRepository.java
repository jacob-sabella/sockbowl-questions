package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.api.input.PacketFilterInput;
import com.soulsoftworks.sockbowlquestions.dto.PacketOwnerDto;
import com.soulsoftworks.sockbowlquestions.dto.PacketPageDto;
import com.soulsoftworks.sockbowlquestions.dto.PacketSummaryDto;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code packets} list projection (M3 Q5, plan 3.1.9, PB-19). Uses {@link Neo4jClient}
 * directly rather than a repository {@code @Query}, because the filter, the visibility
 * {@code WHERE} and pagination all vary per call: repository {@code @Query} methods take a
 * fixed statement, and Spring Data Neo4j's single-column {@code @Query} mapping can't
 * return the multi-column, aggregated row this projection needs anyway (see
 * {@code TaxonomyService}/{@code TaxonomySchemaInitializer} for the same reasoning).
 *
 * <p>Every row is computed by one Cypher statement (built per call from the filter and the
 * caller's read rights) and one matching count statement, both templated from
 * {@link #matchAndFilter}, so the total and the page always agree. Never touches
 * {@code PacketAuthoringService}: this is a read-only projection, and the caller can't
 * write through it.
 */
@Repository
public class PacketSummaryRepository {

    /** Schema default and hard cap for {@code size} (plan 3.1.9: "size is capped at 100"). */
    static final int DEFAULT_SIZE = 25;
    static final int MAX_SIZE = 100;

    private final Neo4jClient neo4j;
    private final PacketReadPolicy readPolicy;

    public PacketSummaryRepository(Neo4jClient neo4j, PacketReadPolicy readPolicy) {
        this.neo4j = neo4j;
        this.readPolicy = readPolicy;
    }

    /**
     * Runs the {@code packets} query for the current security context.
     *
     * @param filter   may be null (no restriction beyond the caller's read rights)
     * @param pageArg  0-based; null or negative counts as 0
     * @param sizeArg  null, zero or negative falls back to {@value #DEFAULT_SIZE};
     *                 anything above {@value #MAX_SIZE} is capped there
     */
    public PacketPageDto find(PacketFilterInput filter, Integer pageArg, Integer sizeArg) {
        int page = clampPage(pageArg);
        int size = clampSize(sizeArg);

        Authentication auth = PacketReadPolicy.currentAuthentication();
        String callerId = readPolicy.callerId(auth);
        boolean mine = filter != null && Boolean.TRUE.equals(filter.mine());
        if (mine && callerId == null) {
            // "mine (ownerId = caller sub; needs auth, otherwise empty)" -- plan 3.1.9.
            return new PacketPageDto(List.of(), 0, page, size);
        }

        boolean canReadEveryPacket = readPolicy.canReadEveryPacket(auth);
        Map<String, Object> params = new HashMap<>();
        params.put("legacyVisibility", readPolicy.legacyVisibility());
        params.put("unlistedVisibilities", readPolicy.unlistedVisibilities());
        if (!canReadEveryPacket) {
            params.put("publicVisibilities", readPolicy.publiclyReadableVisibilities());
            params.put("ownerId", callerId);
        }

        String where = buildWhere(filter, canReadEveryPacket, callerId, mine, params);
        String playableWhere = (filter != null && Boolean.TRUE.equals(filter.playableOnly()))
                ? "WHERE playable" : "";
        String base = matchAndFilter(where, playableWhere);

        long total = neo4j.query(base + "RETURN count(p) AS total")
                .bindAll(params)
                .fetch().one()
                .map(row -> ((Number) row.get("total")).longValue())
                .orElse(0L);

        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("skip", (long) page * size);
        pageParams.put("limit", size);
        Collection<Map<String, Object>> rows = neo4j.query(base + """
                        RETURN p.id AS id, p.name AS name, d.id AS difficultyId, d.name AS difficultyName,
                               p.ownerId AS ownerId, p.ownerDisplayName AS ownerDisplayName,
                               effectiveVisibility AS visibility, coalesce(p.version, 0) AS version,
                               tossupCount, bonusCount, playable
                        ORDER BY toLower(coalesce(p.name, '')), p.id
                        SKIP $skip LIMIT $limit
                        """)
                .bindAll(pageParams)
                .fetch().all();

        List<PacketSummaryDto> items = rows.stream().map(PacketSummaryRepository::toDto).toList();
        return new PacketPageDto(items, (int) total, page, size);
    }

    /**
     * Every clause up to (and including) the {@code playable} computation, shared by the
     * count and data queries so they can never disagree. {@code effectiveVisibility} and
     * {@code playable} are computed once, in Cypher, from the same relationships
     * {@code PacketValidator} reads in Java: {@code playable} is
     * {@code tossupCount > 0 AND bonusesWithoutParts = 0} (plan 3.1.9), matching
     * {@code PacketValidator}'s {@code NO_TOSSUPS}/{@code BONUS_WITHOUT_PARTS} ERRORs.
     */
    private static String matchAndFilter(String where, String playableWhere) {
        return """
                MATCH (p:Packet)
                OPTIONAL MATCH (p)-[:DIFFICULTY_LEVEL]->(d:Difficulty)
                WITH p, d, coalesce(p.visibility, $legacyVisibility) AS effectiveVisibility
                WHERE %s
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t:Tossup)
                WITH p, d, effectiveVisibility, count(DISTINCT t) AS tossupCount
                OPTIONAL MATCH (p)-[:CONTAINS_BONUS]->(b:Bonus)
                OPTIONAL MATCH (b)-[:HAS_PART]->(bp:BonusPart)
                WITH p, d, effectiveVisibility, tossupCount, b, count(bp) AS partCount
                WITH p, d, effectiveVisibility, tossupCount, count(b) AS bonusCount,
                     sum(CASE WHEN b IS NOT NULL AND partCount = 0 THEN 1 ELSE 0 END) AS bonusesWithoutParts
                WITH p, d, effectiveVisibility, tossupCount, bonusCount,
                     (tossupCount > 0 AND bonusesWithoutParts = 0) AS playable
                %s
                """.formatted(where, playableWhere);
    }

    /**
     * Builds the {@code WHERE} evaluated right after {@code effectiveVisibility} is known
     * (before the tossup/bonus expansion, so it filters as early as possible), and adds
     * its parameters to {@code params}.
     *
     * <p>Game-only (EPHEMERAL) packets are excluded unconditionally (D15), even for
     * {@code manage-any} or the service token: this projection is a list, and
     * {@link PacketReadPolicy#unlistedVisibilities()} never belongs in one. The read-rights
     * clause is skipped entirely when the caller {@link PacketReadPolicy#canReadEveryPacket
     * may read every packet}, matching {@code getAllPackets}'s same split.
     */
    private static String buildWhere(PacketFilterInput filter, boolean canReadEveryPacket, String callerId,
                                     boolean mine, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("NOT effectiveVisibility IN $unlistedVisibilities");

        if (!canReadEveryPacket) {
            where.append(" AND (effectiveVisibility IN $publicVisibilities")
                    .append(" OR ($ownerId IS NOT NULL AND p.ownerId = $ownerId))");
        }

        if (filter != null && filter.nameContains() != null && !filter.nameContains().isBlank()) {
            where.append(" AND toLower(p.name) CONTAINS toLower($nameContains)");
            params.put("nameContains", filter.nameContains());
        }
        if (filter != null && filter.difficultyId() != null && !filter.difficultyId().isBlank()) {
            where.append(" AND d.id = $difficultyId");
            params.put("difficultyId", filter.difficultyId());
        }
        if (filter != null && filter.visibility() != null) {
            where.append(" AND effectiveVisibility = $filterVisibility");
            params.put("filterVisibility", filter.visibility().name());
        }
        if (mine) {
            where.append(" AND p.ownerId = $callerId");
            params.put("callerId", callerId);
        }
        return where.toString();
    }

    private static PacketSummaryDto toDto(Map<String, Object> row) {
        String difficultyId = (String) row.get("difficultyId");
        Difficulty difficulty = difficultyId == null ? null
                : Difficulty.builder().id(difficultyId).name((String) row.get("difficultyName")).build();
        String ownerId = (String) row.get("ownerId");
        PacketOwnerDto owner = ownerId == null ? null
                : new PacketOwnerDto(ownerId, (String) row.get("ownerDisplayName"));
        return new PacketSummaryDto(
                (String) row.get("id"),
                (String) row.get("name"),
                difficulty,
                owner,
                PacketVisibility.valueOf((String) row.get("visibility")),
                ((Number) row.get("version")).intValue(),
                ((Number) row.get("tossupCount")).intValue(),
                ((Number) row.get("bonusCount")).intValue(),
                (Boolean) row.get("playable"));
    }

    private static int clampPage(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    private static int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}

package com.soulsoftworks.sockbowlquestions.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-subject content counts for the admin usage view (M4-AD-01; plan m4-limits
 * section 2.8): packets owned ({@code Packet.ownerId}) and questions created
 * ({@code Tossup} and {@code Bonus} nodes whose {@code createdBy} is the
 * subject; bonus parts are not counted separately). Nodes from before M4 have
 * no {@code createdBy} (D13) and are not counted as anyone's.
 *
 * <p>Two aggregate queries per call, whatever the number of subjects.
 */
@Repository
public class ContentCountsRepository {

    /** One subject's counts. */
    public record ContentCounts(long packetsOwned, long questionsCreated) {
    }

    private final Neo4jClient neo4j;

    public ContentCountsRepository(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    /**
     * @param subs distinct subjects
     * @return every requested subject (zero counts included), in the given order
     */
    public Map<String, ContentCounts> countsFor(Collection<String> subs) {
        List<String> list = List.copyOf(subs);
        Map<String, Long> packets = count("""
                MATCH (p:Packet) WHERE p.ownerId IN $subs
                RETURN p.ownerId AS sub, count(p) AS n
                """, list);
        Map<String, Long> questions = count("""
                MATCH (q) WHERE (q:Tossup OR q:Bonus) AND q.createdBy IN $subs
                RETURN q.createdBy AS sub, count(q) AS n
                """, list);
        Map<String, ContentCounts> result = new LinkedHashMap<>();
        for (String sub : list) {
            result.put(sub, new ContentCounts(packets.getOrDefault(sub, 0L), questions.getOrDefault(sub, 0L)));
        }
        return result;
    }

    private Map<String, Long> count(String cypher, List<String> subs) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (subs.isEmpty()) {
            return counts;
        }
        neo4j.query(cypher)
                .bind(subs).to("subs")
                .fetch()
                .all()
                .forEach(row -> counts.put((String) row.get("sub"), ((Number) row.get("n")).longValue()));
        return counts;
    }
}

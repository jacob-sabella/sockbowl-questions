package com.soulsoftworks.sockbowlquestions.support;

import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The fixture graph shared by the M3 GraphQL authorization suites (M3 Q6, PB-21):
 * {@code M3GraphQlAuthorizationTest} (auth on) and {@code M3GraphQlAuthOffTest}.
 * Every node id and name starts with a per-case prefix, so cases never collide with each
 * other or with other classes sharing the Neo4j container.
 *
 * <ul>
 *   <li>Packets {@code draft} (DRAFT) and {@code pub} (PUBLISHED) owned by {@code ownerSub};
 *       {@code ownerless} (DRAFT, no owner, D3); {@code eph} (EPHEMERAL, no owner, D15).
 *       Each has version 0, difficulty {@code diff}, one tossup ({@code <key>-t}) and one
 *       bonus ({@code <key>-b}) with three parts, both in subcategory {@code sub}.</li>
 *   <li>Categories {@code cat} (subcategories {@code sub}, {@code subB}) and {@code cat2}
 *       ({@code sub2}); difficulties {@code diff} and {@code diff2}.</li>
 * </ul>
 */
public final class M3AuthFixtures {

    /** A small, well-formed plaintext packet (1 tossup, 1 three-part bonus). */
    public static final String IMPORT_TEXT = """
            M3 auth packet

            TOSSUPS

            1. This M3 authorization tossup asks a question?
            ANSWER: The imported answer

            BONUSES

            1. For 10 points each:
            [10] Part one?
            ANSWER: One
            [10] Part two?
            ANSWER: Two
            [10] Part three?
            ANSWER: Three
            """;

    private M3AuthFixtures() {
    }

    public static String newPrefix() {
        return "m3auth-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    }

    public static void seed(Neo4jClient neo4j, String prefix, String ownerSub) {
        neo4j.query("""
                CREATE (cat:Category {id: $p + 'cat', name: $p + 'Cat', nameKey: toLower($p + 'Cat')})
                CREATE (cat2:Category {id: $p + 'cat2', name: $p + 'Cat2', nameKey: toLower($p + 'Cat2')})
                CREATE (sub:Subcategory {id: $p + 'sub', name: $p + 'Sub', nameKey: toLower($p + 'Sub')})-[:SUBCATEGORY_OF]->(cat)
                CREATE (:Subcategory {id: $p + 'subB', name: $p + 'SubB', nameKey: toLower($p + 'SubB')})-[:SUBCATEGORY_OF]->(cat)
                CREATE (:Subcategory {id: $p + 'sub2', name: $p + 'Sub2', nameKey: toLower($p + 'Sub2')})-[:SUBCATEGORY_OF]->(cat2)
                CREATE (diff:Difficulty {id: $p + 'diff', name: $p + 'Diff', nameKey: toLower($p + 'Diff')})
                CREATE (:Difficulty {id: $p + 'diff2', name: $p + 'Diff2', nameKey: toLower($p + 'Diff2')})
                WITH sub, diff
                UNWIND [
                  {key: 'draft', owner: $owner, visibility: 'DRAFT'},
                  {key: 'pub', owner: $owner, visibility: 'PUBLISHED'},
                  {key: 'ownerless', owner: null, visibility: 'DRAFT'},
                  {key: 'eph', owner: null, visibility: 'EPHEMERAL'}
                ] AS row
                CREATE (pk:Packet {id: $p + row.key, name: $p + row.key, visibility: row.visibility, version: 0})
                SET pk.ownerId = row.owner, pk.ownerDisplayName = CASE WHEN row.owner IS NULL THEN null ELSE 'author' END
                CREATE (pk)-[:DIFFICULTY_LEVEL]->(diff)
                CREATE (pk)-[:CONTAINS_TOSSUP {order: 0}]->(t:Tossup {id: $p + row.key + '-t',
                        question: 'Question ' + row.key + '?', answer: 'Answer-' + row.key})-[:SUBCATEGORY_IS]->(sub)
                CREATE (pk)-[:CONTAINS_BONUS {order: 0}]->(b:Bonus {id: $p + row.key + '-b', preamble: 'For 10 points each:'})
                CREATE (sub)-[:SUBCATEGORY_IS]->(b)
                WITH b, row
                UNWIND range(0, 2) AS i
                CREATE (b)-[:HAS_PART {order: i}]->(:BonusPart {id: $p + row.key + '-bp' + i,
                        question: 'Part ' + i + '?', answer: 'BonusAnswer-' + row.key + '-' + i})
                                """)
                .bind(prefix).to("p")
                .bind(ownerSub).to("owner")
                .run();
    }

    /** Removes every packet (with its content) and node whose id or name starts with {@code prefix}. */
    public static void clean(Neo4jClient neo4j, String prefix) {
        neo4j.query("""
                MATCH (pk:Packet) WHERE pk.id STARTS WITH $p OR pk.name STARTS WITH $p
                OPTIONAL MATCH (pk)-[:CONTAINS_TOSSUP]->(t)
                OPTIONAL MATCH (pk)-[:CONTAINS_BONUS]->(b)
                OPTIONAL MATCH (b)-[:HAS_PART]->(bp)
                DETACH DELETE pk, t, b, bp
                """).bind(prefix).to("p").run();
        neo4j.query("MATCH (n) WHERE n.id STARTS WITH $p OR n.name STARTS WITH $p DETACH DELETE n")
                .bind(prefix).to("p").run();
    }

    /**
     * A stable, comparable picture of every node whose id or name starts with
     * {@code prefix}: labels, properties and outgoing relationships (type, target id and
     * {@code order}). A denied or read-only operation must leave it unchanged.
     */
    public static List<String> snapshot(Neo4jClient neo4j, String prefix) {
        return neo4j.query("""
                        MATCH (n) WHERE n.id STARTS WITH $p OR n.name STARTS WITH $p
                        OPTIONAL MATCH (n)-[r]->(m)
                        RETURN labels(n) AS labels, properties(n) AS props,
                               collect(type(r) + '>' + coalesce(m.id, '?') + '#' + coalesce(toString(r.order), '')) AS out
                        """)
                .bind(prefix).to("p")
                .fetch().all()
                .stream()
                .map(row -> {
                    List<String> out = new ArrayList<>(((Collection<?>) row.get("out")).stream()
                            .map(String::valueOf).toList());
                    out.sort(null);
                    return row.get("labels") + " " + new TreeMap<>((Map<?, ?>) row.get("props")) + " " + out;
                })
                .sorted()
                .toList();
    }
}

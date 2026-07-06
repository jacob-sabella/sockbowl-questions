package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.graphql.data.GraphQlRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@GraphQlRepository
public interface PacketRepository extends Neo4jRepository<Packet, String> {
    Packet getPacketById(String id);

    @Query("MATCH (p:Packet) WHERE toLower(p.name) CONTAINS toLower($name) RETURN p")
    List<Packet> searchByName(String name);

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
     * @return the new packet's id
     */
    @Query("""
            MERGE (d:Difficulty {name: $difficultyName})
              ON CREATE SET d.id = randomUUID()
            CREATE (p:Packet {id: randomUUID(), name: $packetName})
            CREATE (p)-[:DIFFICULTY_LEVEL]->(d)
            WITH p
            CALL (p) {
              UNWIND $tossups AS t
                MERGE (cat:Category {name: t.category})
                  ON CREATE SET cat.id = randomUUID()
                MERGE (cat)<-[:SUBCATEGORY_OF]-(sub:Subcategory {name: t.subcategory})
                  ON CREATE SET sub.id = randomUUID()
                CREATE (tu:Tossup {id: randomUUID(), question: t.question, answer: t.answer, remoteId: t.remoteId})
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
                CREATE (bo:Bonus {id: randomUUID(), preamble: b.preamble, remoteId: b.remoteId})
                CREATE (sub)-[:SUBCATEGORY_IS]->(bo)
                CREATE (p)-[:CONTAINS_BONUS {order: b.order}]->(bo)
                WITH bo, b
                UNWIND b.parts AS part
                  CREATE (bp:BonusPart {id: randomUUID(), question: part.question, answer: part.answer})
                  CREATE (bo)-[:HAS_PART {order: part.order}]->(bp)
            }
            RETURN p.id AS id
            """)
    String batchCreatePacket(@Param("packetName") String packetName,
                             @Param("difficultyName") String difficultyName,
                             @Param("tossups") List<Map<String, Object>> tossups,
                             @Param("bonuses") List<Map<String, Object>> bonuses);
}

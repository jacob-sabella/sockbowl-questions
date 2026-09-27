package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.client.dto.QbRandomFilter;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D13/M4-PV-01 for the raw-Cypher qbreader import path
 * ({@link PacketRepository#batchCreatePacket}): every node the import creates is
 * stamped with {@code source=QBREADER_IMPORT} and the resolved caller as
 * {@code createdBy}/{@code lastModifiedBy}, even though this write bypasses SDN's
 * save pipeline entirely (no {@code @EnableNeo4jAuditing} callback runs for it).
 */
@SpringBootTest
class QbreaderImportProvenanceTest extends Neo4jContainerTestBase {

    private static final String TAG = "qb-import-prov";

    @Autowired private QbreaderImportService importService;
    @Autowired private PacketRepository packetRepository;
    @Autowired private Neo4jClient neo4j;

    @BeforeEach
    void seedBank() {
        neo4j.query("""
                CREATE (:BankTossup {remoteId: $tag + '-t1', question: 'Q?', answer: 'A',
                                     category: 'Literature', subcategory: 'British Literature',
                                     standard: true, testTag: $tag})
                CREATE (b:BankBonus {remoteId: $tag + '-b1', preamble: 'Pre',
                                     category: 'Literature', subcategory: 'British Literature',
                                     standard: true, testTag: $tag})
                CREATE (bp:BankBonusPart {question: 'BQ?', answer: 'BA', testTag: $tag})
                CREATE (b)-[:HAS_PART {order: 0}]->(bp)
                """).bind(TAG).to("tag").run();
    }

    @AfterEach
    void cleanUp() {
        neo4j.query("""
                MATCH (n) WHERE n.testTag = $tag
                OPTIONAL MATCH (n)-[:HAS_PART]->(bp:BankBonusPart)
                DETACH DELETE n, bp
                """).bind(TAG).to("tag").run();
        neo4j.query("""
                MATCH (p:Packet) WHERE p.name STARTS WITH $tag
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t) OPTIONAL MATCH (p)-[:CONTAINS_BONUS]->(b)
                OPTIONAL MATCH (b)-[:HAS_PART]->(bp)
                DETACH DELETE p, t, b, bp
                """).bind(TAG).to("tag").run();
        SecurityContextHolder.clearContext();
    }

    @Test
    void importedPacketAndItsQuestionsAreStampedWithQbreaderImportProvenance() {
        // The 3-arg (authorities) constructor is the one that marks the token
        // authenticated; the 2-arg one defaults to unauthenticated.
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("qb-import-caller", "n/a", java.util.List.of()));

        QbRandomFilter filter = new QbRandomFilter(null, null, null, null, null, null, null);
        QbreaderImportService.ImportOutcome outcome = importService.importRandomPacket(
                filter, 1, 1, TAG + " packet", java.util.List.of(), false, null, null);

        Packet loaded = packetRepository.findById(outcome.packet().getId()).orElseThrow();
        assertThat(loaded.getSource()).isEqualTo(ContentSource.QBREADER_IMPORT);
        assertThat(loaded.getCreatedBy()).isEqualTo("qb-import-caller");
        assertThat(loaded.getLastModifiedBy()).isEqualTo("qb-import-caller");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getAiModel()).isNull();

        Tossup tossup = loaded.getTossups().get(0).getTossup();
        assertThat(tossup.getSource()).isEqualTo(ContentSource.QBREADER_IMPORT);
        assertThat(tossup.getCreatedBy()).isEqualTo("qb-import-caller");

        Bonus bonus = loaded.getBonuses().get(0).getBonus();
        assertThat(bonus.getSource()).isEqualTo(ContentSource.QBREADER_IMPORT);
        assertThat(bonus.getCreatedBy()).isEqualTo("qb-import-caller");
        assertThat(bonus.getBonusParts().get(0).getBonusPart().getSource()).isEqualTo(ContentSource.QBREADER_IMPORT);
        assertThat(bonus.getBonusParts().get(0).getBonusPart().getCreatedBy()).isEqualTo("qb-import-caller");
    }

    @Test
    void anonymousImportIsStampedAsAnonymous() {
        // No authentication (guest import-random, D15).
        QbRandomFilter filter = new QbRandomFilter(null, null, null, null, null, null, null);
        QbreaderImportService.ImportOutcome outcome = importService.importRandomPacket(
                filter, 1, 0, TAG + " anon packet", java.util.List.of(), false, null, null);

        Packet loaded = packetRepository.findById(outcome.packet().getId()).orElseThrow();
        assertThat(loaded.getCreatedBy()).isEqualTo("anonymous");
    }
}

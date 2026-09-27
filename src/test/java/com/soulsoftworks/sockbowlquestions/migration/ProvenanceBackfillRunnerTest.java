package com.soulsoftworks.sockbowlquestions.migration;

import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D13/M4-PV-01 backfill against a real Neo4j: legacy owned packets get
 * {@code createdBy}/{@code lastModifiedBy} backfilled from {@code ownerId}, ownerless
 * legacy packets are left alone, {@code ownerId} itself is never touched, and a second
 * run changes nothing.
 */
@SpringBootTest
class ProvenanceBackfillRunnerTest extends Neo4jContainerTestBase {

    private static final String TAG = "ProvenanceBackfillRunnerTest";

    @Autowired private ProvenanceBackfillRunner runner;
    @Autowired private Neo4jClient neo4j;

    @BeforeEach
    @AfterEach
    void clean() {
        // The runner also runs on context start; run it once more so only this test's
        // nodes are counted below, then remove anything this test created.
        neo4j.query("MATCH (p:Packet {testTag: $tag}) DETACH DELETE p").bind(TAG).to("tag").run();
        runner.migrate();
    }

    @Test
    void ownedLegacyPacketsAreBackfilledFromOwnerId() {
        neo4j.query("""
                CREATE (:Packet {id: 'prov-mig-owned-1', name: 'owned 1', ownerId: 'prov-mig-owner-1', testTag: $tag})
                CREATE (:Packet {id: 'prov-mig-owned-2', name: 'owned 2', ownerId: 'prov-mig-owner-2', testTag: $tag})
                CREATE (:Packet {id: 'prov-mig-ownerless', name: 'ownerless', testTag: $tag})
                CREATE (:Packet {id: 'prov-mig-already-stamped', name: 'already stamped',
                                 ownerId: 'prov-mig-owner-3', createdBy: 'someone-else',
                                 lastModifiedBy: 'someone-else', testTag: $tag})
                """).bind(TAG).to("tag").run();

        long updated = runner.migrate();

        assertThat(updated).isEqualTo(2);
        Map<String, Map<String, Object>> rows = provenance();
        assertThat(rows.get("prov-mig-owned-1")).containsEntry("createdBy", "prov-mig-owner-1")
                .containsEntry("lastModifiedBy", "prov-mig-owner-1").containsEntry("ownerId", "prov-mig-owner-1");
        assertThat(rows.get("prov-mig-owned-2")).containsEntry("createdBy", "prov-mig-owner-2")
                .containsEntry("lastModifiedBy", "prov-mig-owner-2");
        // Ownerless: no signal to attribute to, so left alone.
        assertThat(rows.get("prov-mig-ownerless").get("createdBy")).isNull();
        // Already stamped: never overwritten, and ownerId is untouched either way.
        assertThat(rows.get("prov-mig-already-stamped")).containsEntry("createdBy", "someone-else")
                .containsEntry("ownerId", "prov-mig-owner-3");
    }

    @Test
    void isIdempotent() {
        neo4j.query("CREATE (:Packet {id: 'prov-mig-idem', name: 'idem', ownerId: 'prov-mig-idem-owner', testTag: $tag})")
                .bind(TAG).to("tag").run();

        assertThat(runner.migrate()).isEqualTo(1);
        assertThat(runner.migrate()).isZero();
        assertThat(provenance().get("prov-mig-idem")).containsEntry("createdBy", "prov-mig-idem-owner")
                .containsEntry("ownerId", "prov-mig-idem-owner");
    }

    private Map<String, Map<String, Object>> provenance() {
        return neo4j.query("""
                        MATCH (p:Packet {testTag: $tag})
                        RETURN p.id AS id, p.ownerId AS ownerId, p.createdBy AS createdBy, p.lastModifiedBy AS lastModifiedBy
                        """)
                .bind(TAG).to("tag")
                .fetch().all().stream()
                .collect(java.util.stream.Collectors.toMap(r -> (String) r.get("id"), r -> r));
    }
}

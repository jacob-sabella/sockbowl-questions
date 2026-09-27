package com.soulsoftworks.sockbowlquestions.config;

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
 * D2 backfill against a real Neo4j: packets without a visibility become PUBLISHED,
 * packets that already have one are left alone, and a second run changes nothing.
 */
@SpringBootTest
class PacketVisibilityMigrationTest extends Neo4jContainerTestBase {

    private static final String TAG = "PacketVisibilityMigrationTest";

    @Autowired private PacketVisibilityMigration migration;
    @Autowired private Neo4jClient neo4j;

    @BeforeEach
    @AfterEach
    void clean() {
        // The migration runs on context start too; run it once more so only this
        // test's nodes can be counted below, then remove anything we created.
        neo4j.query("MATCH (p:Packet {testTag: $tag}) DETACH DELETE p").bind(TAG).to("tag").run();
        migration.migrate();
    }

    @Test
    void nullBecomesPublishedAndExistingValuesAreKept() {
        neo4j.query("""
                CREATE (:Packet {id: 'mig-legacy-1', name: 'legacy 1', testTag: $tag})
                CREATE (:Packet {id: 'mig-legacy-2', name: 'legacy 2', testTag: $tag})
                CREATE (:Packet {id: 'mig-draft', name: 'draft', visibility: 'DRAFT', testTag: $tag})
                CREATE (:Packet {id: 'mig-pub', name: 'pub', visibility: 'PUBLISHED', testTag: $tag})
                """).bind(TAG).to("tag").run();

        long updated = migration.migrate();

        assertThat(updated).isEqualTo(2);
        assertThat(visibilities()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "mig-legacy-1", "PUBLISHED",
                "mig-legacy-2", "PUBLISHED",
                "mig-draft", "DRAFT",
                "mig-pub", "PUBLISHED"));
    }

    @Test
    void isIdempotent() {
        neo4j.query("CREATE (:Packet {id: 'mig-legacy-3', name: 'legacy 3', testTag: $tag})")
                .bind(TAG).to("tag").run();

        assertThat(migration.migrate()).isEqualTo(1);
        assertThat(migration.migrate()).isZero();
        assertThat(visibilities()).containsExactlyEntriesOf(Map.of("mig-legacy-3", "PUBLISHED"));
    }

    private Map<String, Object> visibilities() {
        return neo4j.query("MATCH (p:Packet {testTag: $tag}) RETURN p.id AS id, p.visibility AS visibility")
                .bind(TAG).to("tag")
                .fetch().all().stream()
                .collect(java.util.stream.Collectors.toMap(r -> (String) r.get("id"), r -> r.get("visibility")));
    }
}

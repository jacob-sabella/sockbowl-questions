package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The D2 visibility Cypher against a real Neo4j: the list/search filters, the stored
 * visibility of new packets, and that the transient {@code answersRedacted} flag is
 * never persisted.
 */
@SpringBootTest
class PacketRepositoryVisibilityIT extends Neo4jContainerTestBase {

    private static final String PREFIX = "repo-vis-";
    /** Every packet name this test creates starts with this, so cleanup can find them. */
    private static final String NAME_PREFIX = "repo-vis ";
    private static final String OWNER = "repo-vis-owner";

    @Autowired private PacketRepository repository;
    @Autowired private PacketReadPolicy policy;
    @Autowired private Neo4jClient neo4j;

    @BeforeEach
    void seed() {
        clean();
        neo4j.query("""
                CREATE (:Packet {id: 'repo-vis-draft-mine', name: 'repo-vis Alpha', visibility: 'DRAFT', ownerId: $owner})
                CREATE (:Packet {id: 'repo-vis-draft-other', name: 'repo-vis Beta', visibility: 'DRAFT', ownerId: 'someone-else'})
                CREATE (:Packet {id: 'repo-vis-draft-ownerless', name: 'repo-vis Gamma', visibility: 'DRAFT'})
                CREATE (:Packet {id: 'repo-vis-pub', name: 'repo-vis Delta', visibility: 'PUBLISHED', ownerId: 'someone-else'})
                """).bind(OWNER).to("owner").run();
        // A legacy node with no visibility (created after the startup migration ran).
        neo4j.query("CREATE (:Packet {id: 'repo-vis-legacy', name: 'repo-vis Epsilon'})").run();
    }

    @AfterEach
    void clean() {
        neo4j.query("MATCH (p:Packet) WHERE p.id STARTS WITH $prefix OR p.name STARTS WITH $namePrefix DETACH DELETE p")
                .bind(PREFIX).to("prefix").bind(NAME_PREFIX).to("namePrefix").run();
    }

    @Test
    void anonymousSeesPublishedAndLegacyOnly() {
        List<String> ids = ours(repository.findVisiblePacketIds(
                policy.publiclyReadableVisibilities(), policy.legacyVisibility(), null));

        assertThat(ids).containsExactlyInAnyOrder("repo-vis-pub", "repo-vis-legacy");
    }

    @Test
    void ownerAlsoSeesOwnDrafts() {
        List<String> ids = ours(repository.findVisiblePacketIds(
                policy.publiclyReadableVisibilities(), policy.legacyVisibility(), OWNER));

        assertThat(ids).containsExactlyInAnyOrder("repo-vis-pub", "repo-vis-legacy", "repo-vis-draft-mine");
    }

    @Test
    void searchAppliesTheSameFilter() {
        List<String> anonymous = repository.searchVisibleByName("REPO-VIS", policy.publiclyReadableVisibilities(),
                policy.legacyVisibility(), null).stream().map(Packet::getId).toList();
        List<String> owner = repository.searchVisibleByName("repo-vis", policy.publiclyReadableVisibilities(),
                policy.legacyVisibility(), OWNER).stream().map(Packet::getId).toList();

        assertThat(anonymous).containsExactlyInAnyOrder("repo-vis-pub", "repo-vis-legacy");
        assertThat(owner).containsExactlyInAnyOrder("repo-vis-pub", "repo-vis-legacy", "repo-vis-draft-mine");
    }

    @Test
    void batchCreateStoresTheGivenVisibility() {
        String id = repository.batchCreatePacket("repo-vis Batch", "Easy", List.of(), List.of(), OWNER, "owner",
                PacketVisibility.DRAFT.name());

        Packet stored = repository.findById(id).orElseThrow();
        assertThat(stored.getVisibility()).isEqualTo(PacketVisibility.DRAFT);
        assertThat(stored.getOwnerId()).isEqualTo(OWNER);
    }

    @Test
    void savedPacketRoundTripsVisibilityAndNeverPersistsAnswersRedacted() {
        Packet saved = repository.save(Packet.builder().name("repo-vis Saved")
                .visibility(PacketVisibility.PUBLISHED).answersRedacted(true).build());

        Map<String, Object> props = neo4j.query("MATCH (p:Packet {id: $id}) RETURN properties(p) AS props")
                .bind(saved.getId()).to("id").fetch().one().map(PacketRepositoryVisibilityIT::props).orElseThrow();
        assertThat(props).containsEntry("visibility", "PUBLISHED").doesNotContainKey("answersRedacted");
        Packet reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getVisibility()).isEqualTo(PacketVisibility.PUBLISHED);
        assertThat(reloaded.getAnswersRedacted()).isNotEqualTo(Boolean.TRUE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> props(Map<String, Object> row) {
        return (Map<String, Object>) row.get("props");
    }

    private static List<String> ours(List<String> ids) {
        return ids.stream().filter(id -> id.startsWith(PREFIX)).toList();
    }
}

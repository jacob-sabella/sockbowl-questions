package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.security.SecurityAuditorAware;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D13/M4-PV-01 auditing ({@code @EnableNeo4jAuditing} + {@link SecurityAuditorAware})
 * against a real Neo4j: {@code createdBy}/{@code createdAt} are set once on first save
 * and never change; {@code lastModifiedBy}/{@code lastModifiedAt} update on every save.
 * Also documents R6: saving an aggregate touches {@code lastModifiedBy}/{@code At} on
 * every reachable child, not only the one that actually changed (SDN doesn't do
 * per-entity dirty-checking on cascade saves) — accepted rather than worked around.
 *
 * <p>This test drives {@link SecurityContextHolder} directly (no HTTP request), which
 * is exactly what {@link SecurityAuditorAware} reads regardless of how the context got
 * populated.
 */
@SpringBootTest
class PacketProvenanceAuditingIT extends Neo4jContainerTestBase {

    private static final String PREFIX = "prov-audit-";

    @Autowired private PacketRepository packetRepository;
    @Autowired private Neo4jClient neo4j;

    @BeforeEach
    @AfterEach
    void cleanUpAndClearContext() {
        neo4j.query("MATCH (p:Packet) WHERE p.id STARTS WITH $prefix OR p.name STARTS WITH $prefix "
                        + "OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t) OPTIONAL MATCH (p)-[:CONTAINS_BONUS]->(b) "
                        + "DETACH DELETE p, t, b")
                .bind(PREFIX).to("prefix").run();
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String sub, String... authorities) {
        List<GrantedAuthority> granted = new ArrayList<>();
        for (String a : authorities) {
            granted.add(new SimpleGrantedAuthority(a));
        }
        Authentication auth = new TestingAuthenticationToken(sub, "n/a", granted);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void createSetsCreatedByAndCreatedAt() {
        authenticateAs("prov-audit-user-1");

        Packet saved = packetRepository.save(Packet.builder().name(PREFIX + "create").build());

        assertThat(saved.getCreatedBy()).isEqualTo("prov-audit-user-1");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getLastModifiedBy()).isEqualTo("prov-audit-user-1");
        assertThat(saved.getLastModifiedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void updateByAnotherUserChangesLastModifiedButPreservesCreatedBy() {
        authenticateAs("prov-audit-owner");
        Packet created = packetRepository.save(Packet.builder().name(PREFIX + "update").build());
        String originalCreatedBy = created.getCreatedBy();
        var originalCreatedAt = created.getCreatedAt();

        authenticateAs("prov-audit-moderator", "packet:manage-any");
        created.setName(PREFIX + "update renamed");
        Packet updated = packetRepository.save(created);

        assertThat(updated.getCreatedBy()).isEqualTo(originalCreatedBy);
        assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(updated.getLastModifiedBy()).isEqualTo("prov-audit-moderator");
        assertThat(updated.getLastModifiedAt()).isNotEqualTo(originalCreatedAt);
    }

    @Test
    void anonymousCallerIsStampedAsAnonymous() {
        // No authentication at all (guest / auth disabled).
        Packet saved = packetRepository.save(Packet.builder().name(PREFIX + "anon").build());
        assertThat(saved.getCreatedBy()).isEqualTo("anonymous");
    }

    @Test
    void serviceTokenIsStampedWithAServicePrefix() {
        authenticateAs("game-backend-client", "packet:read-answers");
        Packet saved = packetRepository.save(Packet.builder().name(PREFIX + "service").build());
        assertThat(saved.getCreatedBy()).isEqualTo("service:game-backend-client");
    }

    @Test
    void cascadeSaveTouchesLastModifiedOnUntouchedChildren() {
        // R6: documented, not worked around. Saving the packet again - even though
        // only the packet's own name changed - also bumps lastModifiedBy/At on the
        // tossup and bonus reachable from it, because SDN has no per-entity
        // dirty-checking on an aggregate save.
        authenticateAs("prov-audit-author");
        Tossup tossup = Tossup.builder().question("Q").answer("A").source(ContentSource.AUTHORED).build();
        Bonus bonus = new Bonus();
        bonus.setPreamble("Pre");
        bonus.setSource(ContentSource.AUTHORED);
        Packet packet = Packet.builder().name(PREFIX + "cascade")
                .tossup(ContainsTossup.builder().order(0).tossup(tossup).build())
                .bonuses(List.of(new ContainsBonus(0, bonus)))
                .build();
        Packet created = packetRepository.save(packet);
        var tossupCreatedAt = created.getTossups().get(0).getTossup().getLastModifiedAt();
        var bonusCreatedAt = created.getBonuses().get(0).getBonus().getLastModifiedAt();

        authenticateAs("prov-audit-editor");
        created.setName(PREFIX + "cascade renamed");
        Packet resaved = packetRepository.save(created);

        assertThat(resaved.getTossups().get(0).getTossup().getLastModifiedBy()).isEqualTo("prov-audit-editor");
        assertThat(resaved.getTossups().get(0).getTossup().getLastModifiedAt()).isNotEqualTo(tossupCreatedAt);
        assertThat(resaved.getBonuses().get(0).getBonus().getLastModifiedBy()).isEqualTo("prov-audit-editor");
        assertThat(resaved.getBonuses().get(0).getBonus().getLastModifiedAt()).isNotEqualTo(bonusCreatedAt);
    }

    @Test
    void rawCypherPathStampsAllFourNodeTypes() {
        // The raw-Cypher batchCreatePacket path bypasses SDN's save pipeline entirely,
        // so it must stamp provenance itself; this proves it actually lands in Neo4j.
        String auditor = "prov-audit-raw-cypher";
        List<Map<String, Object>> tossups = List.of(Map.of(
                "question", "Q?", "answer", "A", "category", "ProvCat", "subcategory", "ProvSub",
                "remoteId", "", "order", 0));
        List<Map<String, Object>> bonuses = List.of(Map.of(
                "preamble", "Pre", "category", "ProvCat", "subcategory", "ProvSub", "remoteId", "", "order", 0,
                "parts", List.of(Map.of("question", "BQ?", "answer", "BA", "order", 0))));

        String id = packetRepository.batchCreatePacket(PREFIX + "raw", "Easy", tossups, bonuses,
                null, null, "DRAFT", "import-random",
                auditor, java.time.Instant.now().toString(), ContentSource.QBREADER_IMPORT.name());

        Packet loaded = packetRepository.findById(id).orElseThrow();
        assertThat(loaded.getCreatedBy()).isEqualTo(auditor);
        assertThat(loaded.getLastModifiedBy()).isEqualTo(auditor);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getSource()).isEqualTo(ContentSource.QBREADER_IMPORT);

        Tossup tossup = loaded.getTossups().get(0).getTossup();
        assertThat(tossup.getCreatedBy()).isEqualTo(auditor);
        assertThat(tossup.getSource()).isEqualTo(ContentSource.QBREADER_IMPORT);

        Bonus bonus = loaded.getBonuses().get(0).getBonus();
        assertThat(bonus.getCreatedBy()).isEqualTo(auditor);
        assertThat(bonus.getSource()).isEqualTo(ContentSource.QBREADER_IMPORT);
        assertThat(bonus.getBonusParts().get(0).getBonusPart().getCreatedBy()).isEqualTo(auditor);
        assertThat(bonus.getBonusParts().get(0).getBonusPart().getSource()).isEqualTo(ContentSource.QBREADER_IMPORT);
    }
}

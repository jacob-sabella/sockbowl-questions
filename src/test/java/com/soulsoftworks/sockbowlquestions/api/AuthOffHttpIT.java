package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.service.QuestionGenerationService;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Auth-off regression over the real HTTP stack (Q-M2-02): with
 * {@code sockbowl.auth.enabled=false} (the self-hosted default) the whole application
 * runs under {@code NoSecurityConfig}, method security is not engaged, and an
 * anonymous caller can still author packets exactly as before M2: create a packet,
 * run an ownership-gated mutation on someone else's packet, manage the taxonomy and
 * call {@code /api/packets/generate}. Real Neo4j; only the AI provider is mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sockbowl.auth.enabled=false")
class AuthOffHttpIT extends Neo4jContainerTestBase {

    private static final String NAME_PREFIX = "q-authoff-";

    @LocalServerPort
    private int port;

    @Autowired
    private PacketRepository packetRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private Neo4jClient neo4j;

    @MockitoBean
    private QuestionGenerationService questionGenerationService;

    @AfterEach
    void cleanup() {
        neo4j.query("""
                MATCH (n) WHERE (n:Packet OR n:Category OR n:Subcategory OR n:Difficulty) AND n.name STARTS WITH $prefix
                OPTIONAL MATCH (n)-[:CONTAINS_TOSSUP]->(t)
                DETACH DELETE n, t
                """).bind(NAME_PREFIX).to("prefix").run();
    }

    private static String uniqueName() {
        return NAME_PREFIX + UUID.randomUUID();
    }

    private WebTestClient.Builder clientBuilder() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(20));
    }

    /** An anonymous {@code POST /graphql} tester (see KeycloakAuthITBase for why the url is absolute). */
    private HttpGraphQlTester graphQl() {
        return HttpGraphQlTester.builder(clientBuilder()).url("http://localhost:" + port + "/graphql").build();
    }

    private record Owner(String id, String name) {
    }

    private record CreatedPacket(String id, String name, String visibility, Owner owner) {
    }

    @Test
    void anonymousCreatePacketSucceedsAndIsOwnerless() {
        String name = uniqueName();
        CreatedPacket created = graphQl()
                .document("mutation { createPacket(input: {name: \"" + name + "\"}) { id name visibility owner { id name } } }")
                .execute().path("createPacket").entity(CreatedPacket.class).get();

        assertThat(created.name()).isEqualTo(name);
        assertThat(created.owner()).isNull();
        assertThat(packetRepository.existsById(created.id())).isTrue();
    }

    @Test
    void anonymousOwnershipGatedMutationSucceedsOnAnOwnedPacket() {
        List<Map<String, Object>> tossups = List.of(Map.of(
                "question", "Q?", "answer", "A",
                "category", "QAuthOffCat", "subcategory", "QAuthOffSub", "remoteId", "", "order", 0));
        String id = packetRepository.batchCreatePacket(uniqueName(), "Easy", tossups, List.of(),
                "someone-else", "Someone", PacketVisibility.DRAFT.name(), null);
        String renamed = uniqueName();

        graphQl().document("mutation { renamePacket(id: \"" + id + "\", name: \"" + renamed + "\") { id name owner { id } } }")
                .execute()
                .path("renamePacket.name").entity(String.class).isEqualTo(renamed)
                // Auth off: everyone reads in full, so the owner id is visible as before.
                .path("renamePacket.owner.id").entity(String.class).isEqualTo("someone-else");

        assertThat(packetRepository.findById(id)).map(Packet::getName).contains(renamed);
    }

    @Test
    void anonymousTaxonomyMutationsSucceed() {
        Category category = categoryRepository.save(Category.builder().name(uniqueName()).build());

        graphQl().document("mutation { createDifficulty(name: \"" + uniqueName() + "\") { id } }")
                .execute().errors().verify();
        graphQl().document("mutation { createCategory(name: \"" + uniqueName() + "\") { id } }")
                .execute().errors().verify();
        graphQl().document("mutation { createSubcategory(name: \"" + uniqueName() + "\", categoryId: \""
                        + category.getId() + "\") { id } }")
                .execute().errors().verify();
    }

    @Test
    void anonymousGenerateSucceedsAsAGuest() throws Exception {
        Packet generated = new Packet();
        generated.setId("authoff-generated");
        generated.setName("Generated");
        when(questionGenerationService.generatePacket(any(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(generated);

        clientBuilder().build().get().uri("/api/packets/generate?topic=Science&questionCount=2")
                .header("X-API-Key", "test-key")
                .header("X-Model", "test-model")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("authoff-generated"));

        // Guest generation: no owner is recorded.
        verify(questionGenerationService).generatePacket(eq("Science"), isNull(), eq(2), eq(true), any(),
                isNull(), any());
    }
}

package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.service.QuestionGenerationService;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

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

    /** {@code generateAndAddTossup} (in the ownership-gated mutation list below) calls this. */
    @BeforeEach
    void stubAiGeneration() {
        when(questionGenerationService.generateTossup(any(), any(), any(), any()))
                .thenAnswer(inv -> Tossup.builder().question("Generated?").answer("Generated answer").build());
    }

    @AfterEach
    void cleanup() {
        neo4j.query("""
                MATCH (n) WHERE (n:Packet OR n:Category OR n:Subcategory OR n:Difficulty) AND n.name STARTS WITH $prefix
                OPTIONAL MATCH (n)-[:CONTAINS_TOSSUP]->(t)
                OPTIONAL MATCH (n)-[:CONTAINS_BONUS]->(b)
                OPTIONAL MATCH (b)-[:HAS_PART]->(bp)
                DETACH DELETE n, t, b, bp
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
                "someone-else", "Someone", PacketVisibility.DRAFT.name(), null,
                "someone-else", Instant.now().toString(), ContentSource.AUTHORED.name());
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

    /* --------------------------------- taxonomy ---------------------------------- */

    /** Q2-02: the taxonomy GraphQL queries are open with auth off too, not just auth on. */
    @Test
    void anonymousTaxonomyQueriesSucceed() {
        graphQl().document("{ getAllDifficulties { id } getAllCategories { id } getAllSubcategories { id } }")
                .execute().errors().verify();
    }

    /* --------------------------- public bank aggregates --------------------------- */

    /**
     * Q2-02/Q3-02: the bank-aggregate GETs, and {@code POST /api/qbreader/count} (which
     * carries no {@code @PreAuthorize} either), stay public with auth off.
     */
    @Test
    void bankAggregateGetEndpointsSucceedWithAuthOff() {
        WebTestClient client = clientBuilder().build();
        client.get().uri("/api/qbreader/stats").exchange().expectStatus().isOk();
        client.get().uri("/api/qbreader/dimensions").exchange().expectStatus().isOk();
        client.get().uri("/api/qbreader/category-counts").exchange().expectStatus().isOk();
        client.get().uri("/api/qbreader/taxonomy-counts").exchange().expectStatus().isOk();
        client.post().uri("/api/qbreader/count")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange().expectStatus().isOk();
    }

    @Test
    void anonymousGenerateSucceedsAsAGuest() throws Exception {
        Packet generated = new Packet();
        generated.setId("authoff-generated");
        generated.setName("Generated");
        when(questionGenerationService.generatePacket(any(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(generated);

        // M4 (D11): generate is a POST with a JSON body.
        clientBuilder().build().post().uri("/api/packets/generate")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"Science\",\"questionCount\":2}")
                .header("X-API-Key", "test-key")
                .header("X-Model", "test-model")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("authoff-generated"));

        // Guest generation: no owner is recorded.
        verify(questionGenerationService).generatePacket(eq("Science"), isNull(), eq(2), eq(true), any(),
                isNull(), any());
    }

    /* ------------------- Q3-02: per-endpoint auth-off ownership-gated mutations ------------------- */

    private record MutationFixture(String packetId, String tossupId, String bonusId, String bonusPartId,
                                    String difficultyId, String subcategoryId) {
    }

    private record OwnershipMutation(String label, Function<MutationFixture, String> query) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static String fill(String template, MutationFixture f) {
        return template
                .replace("{packetId}", f.packetId())
                .replace("{tossupId}", f.tossupId())
                .replace("{bonusId}", f.bonusId())
                .replace("{bonusPartId}", f.bonusPartId())
                .replace("{difficultyId}", f.difficultyId())
                .replace("{subcategoryId}", f.subcategoryId());
    }

    /**
     * A DRAFT packet owned by someone else, with one tossup and one bonus (with two parts, so
     * removing one stays within the M3 min-1-part rule, PB-11).
     */
    private MutationFixture seedOwnershipFixture() {
        List<Map<String, Object>> tossups = List.of(Map.of(
                "question", "Q?", "answer", "A",
                "category", "QAuthOffOwnCat", "subcategory", "QAuthOffOwnSub", "remoteId", "", "order", 0));
        List<Map<String, Object>> bonuses = List.of(Map.of(
                "preamble", "Pre", "category", "QAuthOffOwnCat", "subcategory", "QAuthOffOwnSub", "remoteId", "", "order", 0,
                "parts", List.of(Map.of("question", "BQ?", "answer", "BA", "order", 0),
                        Map.of("question", "BQ2?", "answer", "BA2", "order", 1))));
        String packetId = packetRepository.batchCreatePacket(uniqueName(), "Easy", tossups, bonuses,
                "someone-else", "Someone", PacketVisibility.DRAFT.name(), null,
                "someone-else", Instant.now().toString(), ContentSource.AUTHORED.name());
        Packet loaded = packetRepository.findById(packetId).orElseThrow();
        var tossup = loaded.getTossups().get(0).getTossup();
        var bonus = loaded.getBonuses().get(0).getBonus();
        return new MutationFixture(packetId, tossup.getId(), bonus.getId(),
                bonus.getBonusParts().get(0).getBonusPart().getId(),
                loaded.getDifficulty().getId(), tossup.getSubcategory().getId());
    }

    /**
     * The same 19 ownership-gated mutations {@code GraphQlMutationAuthorizationIT} checks
     * with auth on, so that (Q3-02) each one also has an explicit auth-off row instead of
     * relying on {@code renamePacket} alone to stand in for the mechanism.
     */
    static List<OwnershipMutation> ownershipGatedMutationsAuthOff() {
        return List.of(
                new OwnershipMutation("renamePacket", f -> fill(
                        "mutation { renamePacket(id: \"{packetId}\", name: \"Renamed\") { id } }", f)),
                new OwnershipMutation("setPacketDifficulty", f -> fill(
                        "mutation { setPacketDifficulty(id: \"{packetId}\", difficultyId: \"{difficultyId}\") { id } }", f)),
                new OwnershipMutation("setPacketVisibility", f -> fill(
                        "mutation { setPacketVisibility(id: \"{packetId}\", visibility: PUBLISHED) { id } }", f)),
                new OwnershipMutation("addTossupToPacket", f -> fill(
                        "mutation { addTossupToPacket(packetId: \"{packetId}\", input: {question: \"New?\", answer: \"NewA\"}) { id } }", f)),
                new OwnershipMutation("updateTossup", f -> fill(
                        "mutation { updateTossup(id: \"{tossupId}\", input: {question: \"Q2?\", answer: \"A2\"}) { id } }", f)),
                new OwnershipMutation("reorderTossup", f -> fill(
                        "mutation { reorderTossup(packetId: \"{packetId}\", tossupId: \"{tossupId}\", newOrder: 0) { id } }", f)),
                new OwnershipMutation("addBonusToPacket", f -> fill(
                        // M3 (PB-11): a new bonus needs at least one part.
                        "mutation { addBonusToPacket(packetId: \"{packetId}\", input: {preamble: \"P2\", "
                                + "parts: [{question: \"NBQ?\", answer: \"NBA\"}]}) { id } }", f)),
                new OwnershipMutation("updateBonus", f -> fill(
                        "mutation { updateBonus(id: \"{bonusId}\", input: {preamble: \"P3\"}) { id } }", f)),
                new OwnershipMutation("reorderBonus", f -> fill(
                        "mutation { reorderBonus(packetId: \"{packetId}\", bonusId: \"{bonusId}\", newOrder: 0) { id } }", f)),
                new OwnershipMutation("addBonusPart", f -> fill(
                        "mutation { addBonusPart(bonusId: \"{bonusId}\", input: {question: \"BQ2?\", answer: \"BA2\"}) { id } }", f)),
                new OwnershipMutation("updateBonusPart", f -> fill(
                        "mutation { updateBonusPart(bonusId: \"{bonusId}\", bonusPartId: \"{bonusPartId}\", input: {question: \"BQ3?\", answer: \"BA3\"}) { id } }", f)),
                new OwnershipMutation("reorderBonusPart", f -> fill(
                        "mutation { reorderBonusPart(bonusId: \"{bonusId}\", bonusPartId: \"{bonusPartId}\", newOrder: 0) { id } }", f)),
                new OwnershipMutation("setTossupSubcategory", f -> fill(
                        "mutation { setTossupSubcategory(tossupId: \"{tossupId}\", subcategoryId: \"{subcategoryId}\") { id } }", f)),
                new OwnershipMutation("setBonusSubcategory", f -> fill(
                        "mutation { setBonusSubcategory(bonusId: \"{bonusId}\", subcategoryId: \"{subcategoryId}\") { id } }", f)),
                new OwnershipMutation("generateAndAddTossup", f -> fill(
                        "mutation { generateAndAddTossup(packetId: \"{packetId}\", input: {topic: \"T\", apiKey: \"k\", model: \"m\"}) { id } }", f)),
                new OwnershipMutation("removeBonusPart", f -> fill(
                        "mutation { removeBonusPart(bonusId: \"{bonusId}\", bonusPartId: \"{bonusPartId}\") { id } }", f)),
                new OwnershipMutation("removeTossupFromPacket", f -> fill(
                        "mutation { removeTossupFromPacket(packetId: \"{packetId}\", tossupId: \"{tossupId}\") { id } }", f)),
                new OwnershipMutation("removeBonusFromPacket", f -> fill(
                        "mutation { removeBonusFromPacket(packetId: \"{packetId}\", bonusId: \"{bonusId}\") { id } }", f)),
                new OwnershipMutation("deletePacket", f -> fill("mutation { deletePacket(id: \"{packetId}\") }", f))
        );
    }

    /**
     * Q3-02: with auth off, every ownership-gated mutation succeeds on someone else's
     * packet, not just {@code renamePacket}. Each case gets its own fresh packet, so a
     * destructive case (e.g. {@code deletePacket}) never starves a later one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ownershipGatedMutationsAuthOff")
    void anonymousOwnershipGatedMutationSucceedsWithAuthOff(OwnershipMutation mutation) {
        MutationFixture fixture = seedOwnershipFixture();
        String doc = mutation.query().apply(fixture);

        graphQl().document(doc).execute().errors().verify();
    }
}

package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.service.QuestionGenerationService;
import com.soulsoftworks.sockbowlquestions.support.KeycloakAuthITBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AUTH-20 (questions): every GraphQL mutation, with real Keycloak tokens, over the
 * real {@code POST /graphql} HTTP path. Anonymous gives {@code UNAUTHORIZED}; a
 * caller with the wrong role or without ownership gives {@code FORBIDDEN}; the
 * owner and {@code packet:manage-any} succeed. The game service token is
 * {@code FORBIDDEN} everywhere, and nobody may edit a game-only EPHEMERAL packet.
 *
 * <p>{@link com.soulsoftworks.sockbowlquestions.api.GraphQlSecurityErrorMappingTest} and
 * {@link GraphQlPacketReadAuthTest} (Q1/Q2) already prove the classification mapping and
 * the D2 read/projection rules at the WebMvc-slice level with mocked JWTs and repositories.
 * This class is the end-to-end proof, over all 23 mutations, with tokens minted by a real
 * Keycloak and packets seeded in a real Neo4j.
 */
class GraphQlMutationAuthorizationIT extends KeycloakAuthITBase {

    private static final String NAME_PREFIX = "q3-mut-";

    @Autowired
    private PacketRepository packetRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private Neo4jClient neo4j;

    /** Mocked so generateAndAddTossup's owner/admin success cases don't call a real AI provider. */
    @MockitoBean
    private QuestionGenerationService questionGenerationService;

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

    /* ------------------------------- fixtures ------------------------------- */

    private record Fixture(String packetId, String tossupId, String bonusId, String bonusPartId,
                            String difficultyId, String subcategoryId) {
    }

    private static String uniqueName() {
        return NAME_PREFIX + UUID.randomUUID();
    }

    /** A DRAFT packet with one tossup and one bonus (with one part), owned by {@code ownerId} (null = ownerless). */
    private Fixture seed(String ownerId) {
        return seed(ownerId, PacketVisibility.DRAFT);
    }

    /** {@link #seed(String)} with the given visibility (EPHEMERAL packets are ownerless, D15). */
    private Fixture seed(String ownerId, PacketVisibility visibility) {
        List<Map<String, Object>> tossups = List.of(Map.of(
                "question", "Q?", "answer", "A",
                "category", "Q3MutCat", "subcategory", "Q3MutSub", "remoteId", "", "order", 0));
        List<Map<String, Object>> bonuses = List.of(Map.of(
                "preamble", "Pre", "category", "Q3MutCat", "subcategory", "Q3MutSub", "remoteId", "", "order", 0,
                "parts", List.of(Map.of("question", "BQ?", "answer", "BA", "order", 0))));
        String packetId = packetRepository.batchCreatePacket(uniqueName(), "Easy", tossups, bonuses,
                ownerId, ownerId == null ? null : "owner-" + ownerId, visibility.name(),
                visibility == PacketVisibility.EPHEMERAL ? "import-random" : null,
                ownerId == null ? "anonymous" : ownerId, Instant.now().toString(),
                (visibility == PacketVisibility.EPHEMERAL ? ContentSource.QBREADER_IMPORT : ContentSource.AUTHORED).name());
        Packet loaded = packetRepository.findById(packetId).orElseThrow();
        var tossup = loaded.getTossups().get(0).getTossup();
        var bonus = loaded.getBonuses().get(0).getBonus();
        return new Fixture(packetId, tossup.getId(), bonus.getId(),
                bonus.getBonusParts().get(0).getBonusPart().getId(),
                loaded.getDifficulty().getId(), tossup.getSubcategory().getId());
    }

    /* --------------------------- ownership-gated mutations --------------------------- */

    private record MutationCase(String label, Function<Fixture, String> query) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static String q(String template, Fixture f) {
        return template
                .replace("{packetId}", f.packetId())
                .replace("{tossupId}", f.tossupId())
                .replace("{bonusId}", f.bonusId())
                .replace("{bonusPartId}", f.bonusPartId())
                .replace("{difficultyId}", f.difficultyId())
                .replace("{subcategoryId}", f.subcategoryId());
    }

    static List<MutationCase> ownershipGatedMutations() {
        return List.of(
                new MutationCase("renamePacket", f -> q(
                        "mutation { renamePacket(id: \"{packetId}\", name: \"Renamed\") { id } }", f)),
                new MutationCase("setPacketDifficulty", f -> q(
                        "mutation { setPacketDifficulty(id: \"{packetId}\", difficultyId: \"{difficultyId}\") { id } }", f)),
                new MutationCase("setPacketVisibility", f -> q(
                        "mutation { setPacketVisibility(id: \"{packetId}\", visibility: PUBLISHED) { id } }", f)),
                new MutationCase("addTossupToPacket", f -> q(
                        "mutation { addTossupToPacket(packetId: \"{packetId}\", input: {question: \"New?\", answer: \"NewA\"}) { id } }", f)),
                new MutationCase("updateTossup", f -> q(
                        "mutation { updateTossup(id: \"{tossupId}\", input: {question: \"Q2?\", answer: \"A2\"}) { id } }", f)),
                new MutationCase("reorderTossup", f -> q(
                        "mutation { reorderTossup(packetId: \"{packetId}\", tossupId: \"{tossupId}\", newOrder: 0) { id } }", f)),
                new MutationCase("addBonusToPacket", f -> q(
                        "mutation { addBonusToPacket(packetId: \"{packetId}\", input: {preamble: \"P2\"}) { id } }", f)),
                new MutationCase("updateBonus", f -> q(
                        "mutation { updateBonus(id: \"{bonusId}\", input: {preamble: \"P3\"}) { id } }", f)),
                new MutationCase("reorderBonus", f -> q(
                        "mutation { reorderBonus(packetId: \"{packetId}\", bonusId: \"{bonusId}\", newOrder: 0) { id } }", f)),
                new MutationCase("addBonusPart", f -> q(
                        "mutation { addBonusPart(bonusId: \"{bonusId}\", input: {question: \"BQ2?\", answer: \"BA2\"}) { id } }", f)),
                new MutationCase("updateBonusPart", f -> q(
                        "mutation { updateBonusPart(bonusId: \"{bonusId}\", bonusPartId: \"{bonusPartId}\", input: {question: \"BQ3?\", answer: \"BA3\"}) { id } }", f)),
                new MutationCase("reorderBonusPart", f -> q(
                        "mutation { reorderBonusPart(bonusId: \"{bonusId}\", bonusPartId: \"{bonusPartId}\", newOrder: 0) { id } }", f)),
                new MutationCase("setTossupSubcategory", f -> q(
                        "mutation { setTossupSubcategory(tossupId: \"{tossupId}\", subcategoryId: \"{subcategoryId}\") { id } }", f)),
                new MutationCase("setBonusSubcategory", f -> q(
                        "mutation { setBonusSubcategory(bonusId: \"{bonusId}\", subcategoryId: \"{subcategoryId}\") { id } }", f)),
                new MutationCase("generateAndAddTossup", f -> q(
                        "mutation { generateAndAddTossup(packetId: \"{packetId}\", input: {topic: \"T\", apiKey: \"k\", model: \"m\"}) { id } }", f)),
                // Destructive mutations: each role-check below runs against its own fresh
                // fixture, so removing/deleting for one role never starves another.
                new MutationCase("removeBonusPart", f -> q(
                        "mutation { removeBonusPart(bonusId: \"{bonusId}\", bonusPartId: \"{bonusPartId}\") { id } }", f)),
                new MutationCase("removeTossupFromPacket", f -> q(
                        "mutation { removeTossupFromPacket(packetId: \"{packetId}\", tossupId: \"{tossupId}\") { id } }", f)),
                new MutationCase("removeBonusFromPacket", f -> q(
                        "mutation { removeBonusFromPacket(packetId: \"{packetId}\", bonusId: \"{bonusId}\") { id } }", f)),
                new MutationCase("deletePacket", f -> q("mutation { deletePacket(id: \"{packetId}\") }", f))
        );
    }

    /**
     * The full D3/PB-17 ownership matrix, for every ownership-gated mutation: anonymous
     * ({@code UNAUTHORIZED}), player and a non-owner author ({@code FORBIDDEN}), an author
     * on an ownerless packet ({@code FORBIDDEN}, no grandfather rule), the owner author
     * (succeeds), and admin via {@code packet:manage-any} on another author's packet
     * (succeeds).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ownershipGatedMutations")
    void ownershipGatedMutation(MutationCase mutation) {
        Fixture ownedByAuthor = seed(AUTHOR_SUB);
        String docAgainstAuthorsPacket = mutation.query().apply(ownedByAuthor);

        assertClassification(graphQlTester(null).document(docAgainstAuthorsPacket).execute(), "UNAUTHORIZED");
        assertClassification(graphQlTester(tokenFor(PLAYER)).document(docAgainstAuthorsPacket).execute(), "FORBIDDEN");
        assertClassification(graphQlTester(tokenFor(AUTHOR2)).document(docAgainstAuthorsPacket).execute(), "FORBIDDEN");
        // The game backend's service token reads everything but may write nothing (Q-M2-03).
        assertClassification(graphQlTester(serviceToken()).document(docAgainstAuthorsPacket).execute(), "FORBIDDEN");

        Fixture ownerless = seed(null);
        assertClassification(graphQlTester(tokenFor(AUTHOR)).document(mutation.query().apply(ownerless)).execute(),
                "FORBIDDEN");

        Fixture forOwnerSuccess = seed(AUTHOR_SUB);
        assertSucceeds(graphQlTester(tokenFor(AUTHOR)).document(mutation.query().apply(forOwnerSuccess)).execute());

        Fixture ownedByAuthor2 = seed(AUTHOR2_SUB);
        assertSucceeds(graphQlTester(tokenFor(ADMIN)).document(mutation.query().apply(ownedByAuthor2)).execute());
    }

    /**
     * D15 over the wire (Q-M2-03): a game-only EPHEMERAL packet is not editable by anyone,
     * not even {@code packet:manage-any}, through any node-level or packet-level mutation.
     * The one exception is {@code deletePacket}, which {@code packet:manage-any} may use.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ownershipGatedMutations")
    void adminCannotEditAnEphemeralPacketButMayDeleteIt(MutationCase mutation) {
        String doc = mutation.query().apply(seed(null, PacketVisibility.EPHEMERAL));
        if (mutation.label().equals("deletePacket")) {
            assertSucceeds(graphQlTester(tokenFor(ADMIN)).document(doc).execute());
        } else {
            assertClassification(graphQlTester(tokenFor(ADMIN)).document(doc).execute(), "FORBIDDEN");
        }
    }

    /* -------------------------------- createPacket -------------------------------- */

    @Test
    void createPacketRequiresPacketCreate() {
        String query = "mutation { createPacket(input: {name: \"" + uniqueName() + "\"}) { id } }";

        assertClassification(graphQlTester(null).document(query).execute(), "UNAUTHORIZED");
        assertClassification(graphQlTester(tokenFor(PLAYER)).document(query).execute(), "FORBIDDEN");
        assertClassification(graphQlTester(serviceToken()).document(query).execute(), "FORBIDDEN");
        assertSucceeds(graphQlTester(tokenFor(AUTHOR)).document(query).execute());
        assertSucceeds(graphQlTester(tokenFor(ADMIN)).document(query).execute());
    }

    /* --------------------------------- taxonomy ------------------------------------ */

    /**
     * Taxonomy mutations aren't ownership-gated (D4's dedup/roles rework is M3's job);
     * in M2 they require only {@code taxonomy:manage}, which the {@code author} and
     * {@code admin} composites carry and {@code player}/{@code moderator} don't.
     */
    @Test
    void taxonomyMutationsFollowTheCurrentRoleMapping() {
        Category category = categoryRepository.save(Category.builder().name(uniqueName()).build());
        List<Function<Void, String>> queries = List.of(
                unused -> "mutation { createDifficulty(name: \"" + uniqueName() + "\") { id } }",
                unused -> "mutation { createCategory(name: \"" + uniqueName() + "\") { id } }",
                unused -> "mutation { createSubcategory(name: \"" + uniqueName() + "\", categoryId: \"" + category.getId() + "\") { id } }"
        );
        for (Function<Void, String> query : queries) {
            String doc = query.apply(null);
            assertClassification(graphQlTester(null).document(doc).execute(), "UNAUTHORIZED");
            assertClassification(graphQlTester(tokenFor(PLAYER)).document(doc).execute(), "FORBIDDEN");
            assertClassification(graphQlTester(tokenFor(MODERATOR)).document(doc).execute(), "FORBIDDEN");
            assertClassification(graphQlTester(serviceToken()).document(doc).execute(), "FORBIDDEN");
            assertSucceeds(graphQlTester(tokenFor(AUTHOR)).document(query.apply(null)).execute());
            assertSucceeds(graphQlTester(tokenFor(ADMIN)).document(query.apply(null)).execute());
        }
    }

    /* --------------------------------- helpers -------------------------------------- */

    private static void assertSucceeds(GraphQlTester.Response response) {
        response.errors().verify();
    }

    private static void assertClassification(GraphQlTester.Response response, String expectedClassification) {
        response.errors()
                .expect(err -> expectedClassification.equals(classificationOf(err)))
                .verify();
    }

    private static String classificationOf(ResponseError error) {
        Object classification = error.getExtensions() == null ? null : error.getExtensions().get("classification");
        return classification == null ? null : classification.toString();
    }
}

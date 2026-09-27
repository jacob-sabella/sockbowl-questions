package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.support.KeycloakAuthITBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTH-20 (questions), D2/AUTH-03 over the real {@code POST /graphql} HTTP path with
 * real Keycloak tokens: who sees a draft, whether answers are stripped, and that the
 * game service token reads everything in full. The Q2 unit suites
 * ({@code PacketReadPolicyTest}, {@code PacketProjectionTest}) already prove the
 * decision logic in isolation; this is the end-to-end proof.
 */
class GraphQlReadAuthorizationIT extends KeycloakAuthITBase {

    private static final String NAME_PREFIX = "q3-read-";
    private static final String DETAIL_FIELDS = "id visibility answersRedacted tossups { tossup { answer } }";

    @Autowired
    private PacketRepository packetRepository;
    @Autowired
    private Neo4jClient neo4j;

    private String searchToken;
    /** DRAFT, owned by {@link KeycloakAuthITBase#AUTHOR}. */
    private String draftId;
    /** PUBLISHED, owned by {@link KeycloakAuthITBase#AUTHOR2} (so the service/admin "another user's" checks have a target). */
    private String publishedId;

    private record TossupAnswer(String answer) {
    }

    private record TossupRef(TossupAnswer tossup) {
    }

    private record PacketDetail(String id, String visibility, boolean answersRedacted, List<TossupRef> tossups) {
    }

    private record PacketId(String id) {
    }

    @BeforeEach
    void seed() {
        searchToken = UUID.randomUUID().toString().substring(0, 8);
        draftId = seedPacket(AUTHOR_SUB, PacketVisibility.DRAFT, "SecretAnswer", "draft");
        publishedId = seedPacket(AUTHOR2_SUB, PacketVisibility.PUBLISHED, "PublicAnswer", "pub");
    }

    @AfterEach
    void cleanup() {
        neo4j.query("""
                MATCH (p:Packet) WHERE p.name STARTS WITH $prefix
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t)
                DETACH DELETE p, t
                """).bind(NAME_PREFIX).to("prefix").run();
    }

    private String seedPacket(String ownerId, PacketVisibility visibility, String answer, String suffix) {
        List<Map<String, Object>> tossups = List.of(Map.of(
                "question", "Q?", "answer", answer,
                "category", "Q3ReadCat", "subcategory", "Q3ReadSub", "remoteId", "", "order", 0));
        return packetRepository.batchCreatePacket(NAME_PREFIX + searchToken + "-" + suffix, "Easy", tossups, List.of(),
                ownerId, "owner-" + ownerId, visibility.name(), null,
                ownerId == null ? "anonymous" : ownerId, Instant.now().toString(), ContentSource.AUTHORED.name());
    }

    /* ------------------------------- getPacketById ------------------------------- */

    @Test
    void anonymousGetPacketByIdOfDraftIsNull() {
        graphQlTester(null).document("{ getPacketById(id: \"" + draftId + "\") { id } }")
                .execute().path("getPacketById").valueIsNull();
    }

    @Test
    void nonOwnerAuthorGetPacketByIdOfDraftIsNull() {
        graphQlTester(tokenFor(AUTHOR2)).document("{ getPacketById(id: \"" + draftId + "\") { id } }")
                .execute().path("getPacketById").valueIsNull();
    }

    @Test
    void ownerSeesOwnDraftWithAnswers() {
        PacketDetail detail = detail(tokenFor(AUTHOR), draftId);
        assertThat(detail.visibility()).isEqualTo("DRAFT");
        assertThat(detail.answersRedacted()).isFalse();
        assertThat(detail.tossups().get(0).tossup().answer()).isEqualTo("SecretAnswer");
    }

    @Test
    void playerGetsPublishedPacketWithAnswersRedacted() {
        PacketDetail detail = detail(tokenFor(PLAYER), publishedId);
        assertThat(detail.visibility()).isEqualTo("PUBLISHED");
        assertThat(detail.answersRedacted()).isTrue();
        assertThat(detail.tossups().get(0).tossup().answer()).isNull();
    }

    @Test
    void serviceTokenReadsFullAnswersOnPublishedAndOnAnotherUsersDraft() {
        for (String id : List.of(publishedId, draftId)) {
            PacketDetail detail = detail(serviceToken(), id);
            assertThat(detail.answersRedacted()).isFalse();
            assertThat(detail.tossups().get(0).tossup().answer()).isNotBlank();
        }
    }

    /* ------------------------------ getAllPackets ------------------------------ */

    @Test
    void anonymousGetAllPacketsExcludesDraftAndRedactsPublishedAnswers() {
        Map<String, PacketDetail> byId = allPackets(null);
        assertThat(byId).doesNotContainKey(draftId);
        PacketDetail pub = byId.get(publishedId);
        assertThat(pub).isNotNull();
        assertThat(pub.answersRedacted()).isTrue();
        assertThat(pub.tossups().get(0).tossup().answer()).isNull();
    }

    @Test
    void ownerGetAllPacketsSeesOwnDraft() {
        Map<String, PacketDetail> byId = allPackets(tokenFor(AUTHOR));
        assertThat(byId).containsKey(draftId);
        assertThat(byId.get(draftId).answersRedacted()).isFalse();
    }

    /* --------------------------- searchPacketsByName --------------------------- */

    @Test
    void searchFollowsTheSameVisibilityRules() {
        List<String> anonymousIds = searchIds(null);
        assertThat(anonymousIds).containsExactly(publishedId);

        List<String> ownerIds = searchIds(tokenFor(AUTHOR));
        assertThat(ownerIds).containsExactlyInAnyOrder(draftId, publishedId);

        List<String> serviceIds = searchIds(serviceToken());
        assertThat(serviceIds).containsExactlyInAnyOrder(draftId, publishedId);
    }

    /* -------------------------------- taxonomy ---------------------------------- */

    @Test
    void taxonomyQueriesAreOpenToAnonymous() {
        graphQlTester(null)
                .document("{ getAllDifficulties { id } getAllCategories { id } getAllSubcategories { id } }")
                .execute().errors().verify();
    }

    /* --------------------------------- helpers ---------------------------------- */

    private PacketDetail detail(String token, String id) {
        return graphQlTester(token).document("{ getPacketById(id: \"" + id + "\") { " + DETAIL_FIELDS + " } }")
                .execute().path("getPacketById").entity(PacketDetail.class).get();
    }

    private Map<String, PacketDetail> allPackets(String token) {
        List<PacketDetail> all = graphQlTester(token).document("{ getAllPackets { " + DETAIL_FIELDS + " } }")
                .execute().path("getAllPackets").entityList(PacketDetail.class).get();
        return all.stream().collect(Collectors.toMap(PacketDetail::id, p -> p, (a, b) -> a));
    }

    private List<String> searchIds(String token) {
        return graphQlTester(token)
                .document("{ searchPacketsByName(name: \"" + searchToken + "\") { id } }")
                .execute().path("searchPacketsByName").entityList(PacketId.class).get()
                .stream().map(PacketId::id).toList();
    }
}

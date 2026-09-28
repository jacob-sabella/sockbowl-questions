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
import java.util.Arrays;
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
    /** EPHEMERAL, ownerless (D15): only the game service token may read it, never manage-any. */
    private String ephemeralId;

    private record TossupAnswer(String answer) {
    }

    private record TossupRef(TossupAnswer tossup) {
    }

    private record PacketDetail(String id, String visibility, boolean answersRedacted, List<TossupRef> tossups) {
    }

    private record PacketId(String id) {
    }

    private record BonusPartAnswer(String answer) {
    }

    private record HasBonusPartRef(BonusPartAnswer bonusPart) {
    }

    private record BonusRef(List<HasBonusPartRef> bonusParts) {
    }

    private record ContainsBonusRef(BonusRef bonus) {
    }

    private record PacketBonusDetail(String id, List<ContainsBonusRef> bonuses) {
    }

    @BeforeEach
    void seed() {
        searchToken = UUID.randomUUID().toString().substring(0, 8);
        draftId = seedPacket(AUTHOR_SUB, PacketVisibility.DRAFT, "SecretAnswer", "draft");
        publishedId = seedPacket(AUTHOR2_SUB, PacketVisibility.PUBLISHED, "PublicAnswer", "pub");
        ephemeralId = seedPacket(null, PacketVisibility.EPHEMERAL, "EphemeralAnswer", "eph");
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
        // Q4-02: every seeded packet also carries one bonus with one part, so the
        // BonusPart.answer redaction path (PacketProjection.answerFree) has real-Neo4j/
        // Keycloak coverage, not just slice/unit tests (GraphQlPacketReadAuthTest,
        // PacketProjectionTest).
        List<Map<String, Object>> bonusParts = List.of(Map.of("question", "BQ?", "answer", bonusAnswer(answer), "order", 0));
        List<Map<String, Object>> bonuses = List.of(Map.of(
                "preamble", "BP?", "category", "Q3ReadCat", "subcategory", "Q3ReadSub", "remoteId", "", "order", 0,
                "parts", bonusParts));
        return packetRepository.batchCreatePacket(NAME_PREFIX + searchToken + "-" + suffix, "Easy", tossups, bonuses,
                ownerId, "owner-" + ownerId, visibility.name(), null,
                ownerId == null ? "anonymous" : ownerId, Instant.now().toString(), ContentSource.AUTHORED.name());
    }

    private static String bonusAnswer(String tossupAnswer) {
        return tossupAnswer + "Bonus";
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

    /* ------------------------------ EPHEMERAL (D15, Q2-02) ----------------------------- */

    /**
     * A game-only packet (D15) is never publicly readable and has no owner to fall
     * back on, so anonymous, player and even manage-any (admin) all get {@code null}:
     * only {@code packet:read-answers} (the game service token) may see it at all.
     */
    @Test
    void anonymousPlayerAndAdminGetPacketByIdOfEphemeralIsNull() {
        for (String token : Arrays.asList(null, tokenFor(PLAYER), tokenFor(ADMIN))) {
            graphQlTester(token).document("{ getPacketById(id: \"" + ephemeralId + "\") { id } }")
                    .execute().path("getPacketById").valueIsNull();
        }
    }

    @Test
    void serviceTokenReadsFullEphemeralPacket() {
        PacketDetail detail = detail(serviceToken(), ephemeralId);
        assertThat(detail.visibility()).isEqualTo("EPHEMERAL");
        assertThat(detail.answersRedacted()).isFalse();
        assertThat(detail.tossups().get(0).tossup().answer()).isEqualTo("EphemeralAnswer");
    }

    @Test
    void ephemeralPacketIsNeverListedOrSearchedEvenForTheServiceToken() {
        for (String token : Arrays.asList(null, tokenFor(PLAYER), tokenFor(ADMIN), serviceToken())) {
            assertThat(allPackets(token)).doesNotContainKey(ephemeralId);
            assertThat(searchIds(token)).doesNotContain(ephemeralId);
        }
    }

    /* --------------------------- bonusPart.answer (Q4-02) --------------------------- */

    /**
     * The same answer redaction rule that applies to a tossup's answer must also apply
     * to a bonus part's answer, over the real GraphQL HTTP path: null for callers who
     * may not read the packet in full, present for the owner and the service token.
     */
    @Test
    void bonusPartAnswerIsRedactedForAnonymousAndPlayerButVisibleToOwnerAndService() {
        for (String token : Arrays.asList(null, tokenFor(PLAYER))) {
            assertThat(bonusPartAnswer(token, publishedId))
                    .as("anonymous/player must not see a bonus part's answer on a PUBLISHED packet")
                    .isNull();
        }
        assertThat(bonusPartAnswer(tokenFor(AUTHOR2), publishedId)).isEqualTo(bonusAnswer("PublicAnswer"));
        assertThat(bonusPartAnswer(serviceToken(), publishedId)).isEqualTo(bonusAnswer("PublicAnswer"));
    }

    private String bonusPartAnswer(String token, String packetId) {
        PacketBonusDetail detail = graphQlTester(token)
                .document("{ getPacketById(id: \"" + packetId + "\") { id bonuses { bonus { bonusParts { bonusPart { answer } } } } } }")
                .execute().path("getPacketById").entity(PacketBonusDetail.class).get();
        return detail.bonuses().get(0).bonus().bonusParts().get(0).bonusPart().answer();
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

    /* ------------------------ owner.id (Q-M2-01, the sub) ------------------------ */

    private record Owner(String id, String name) {
    }

    private record PacketWithOwner(String id, Owner owner) {
    }

    private static final String OWNER_FIELDS = "id owner { id name }";

    /**
     * The owner's id is their Keycloak {@code sub}. Callers who may not read a PUBLISHED
     * packet in full get only the display name, through every read path, so no read
     * enumerates authors' subjects.
     */
    @Test
    void anonymousAndPlayerSeeOwnerNameButNoOwnerIdOnPublishedPacket() {
        for (String token : Arrays.asList(null, tokenFor(PLAYER), tokenFor(AUTHOR))) {
            for (Owner owner : ownersOfPublished(token)) {
                assertThat(owner).isNotNull();
                assertThat(owner.id()).isNull();
                assertThat(owner.name()).isEqualTo("owner-" + AUTHOR2_SUB);
            }
        }
    }

    @Test
    void ownerServiceTokenAndManageAnySeeOwnerIdOnPublishedPacket() {
        for (String token : List.of(tokenFor(AUTHOR2), serviceToken(), tokenFor(ADMIN))) {
            for (Owner owner : ownersOfPublished(token)) {
                assertThat(owner.id()).isEqualTo(AUTHOR2_SUB);
                assertThat(owner.name()).isEqualTo("owner-" + AUTHOR2_SUB);
            }
        }
    }

    @Test
    void ownerSeesOwnIdOnOwnDraft() {
        PacketWithOwner draft = graphQlTester(tokenFor(AUTHOR))
                .document("{ getPacketById(id: \"" + draftId + "\") { " + OWNER_FIELDS + " } }")
                .execute().path("getPacketById").entity(PacketWithOwner.class).get();
        assertThat(draft.owner().id()).isEqualTo(AUTHOR_SUB);
    }

    /** The published packet's owner as seen through getPacketById, getAllPackets and searchPacketsByName. */
    private List<Owner> ownersOfPublished(String token) {
        PacketWithOwner byId = graphQlTester(token)
                .document("{ getPacketById(id: \"" + publishedId + "\") { " + OWNER_FIELDS + " } }")
                .execute().path("getPacketById").entity(PacketWithOwner.class).get();
        PacketWithOwner inAll = graphQlTester(token).document("{ getAllPackets { " + OWNER_FIELDS + " } }")
                .execute().path("getAllPackets").entityList(PacketWithOwner.class).get()
                .stream().filter(p -> publishedId.equals(p.id())).findFirst().orElseThrow();
        PacketWithOwner inSearch = graphQlTester(token)
                .document("{ searchPacketsByName(name: \"" + searchToken + "\") { " + OWNER_FIELDS + " } }")
                .execute().path("searchPacketsByName").entityList(PacketWithOwner.class).get()
                .stream().filter(p -> publishedId.equals(p.id())).findFirst().orElseThrow();
        return Arrays.asList(byId.owner(), inAll.owner(), inSearch.owner());
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

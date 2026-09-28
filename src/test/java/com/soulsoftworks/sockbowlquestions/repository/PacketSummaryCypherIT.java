package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.api.input.PacketFilterInput;
import com.soulsoftworks.sockbowlquestions.dto.PacketPageDto;
import com.soulsoftworks.sockbowlquestions.dto.PacketSummaryDto;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import com.soulsoftworks.sockbowlquestions.service.PacketValidator;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code packets} projection against a real Neo4j (M3 Q5, plan 3.1.9): the Cypher
 * visibility {@code WHERE} built from {@code PacketReadPolicy}, the filters, pagination,
 * and that {@code playable} agrees with {@code PacketValidator}. Fixture: 7 packets across
 * two owners plus one ownerless, DRAFT/PUBLISHED/EPHEMERAL, some unplayable, so every case
 * in plan 3.1.9's acceptance list has data. Constructs its own {@link PacketReadPolicy}
 * (auth on and auth off) and {@link PacketSummaryRepository} instances rather than relying
 * on the app's own {@code sockbowl.auth.enabled} setting, so both are exercised regardless
 * of the local-dev default.
 */
@SpringBootTest
class PacketSummaryCypherIT extends Neo4jContainerTestBase {

    private static final String PREFIX = "q5sum-";
    private static final String NAME_PREFIX = "q5sum ";
    private static final String OWNER_A = "q5sum-owner-a";
    private static final String OWNER_B = "q5sum-owner-b";
    private static final String DIFF_ID = "q5sum-diff-1";

    @Autowired private Neo4jClient neo4j;
    @Autowired private PacketRepository packetRepository;
    @Autowired private PacketValidator validator;

    private PacketSummaryRepository authOnRepo;
    private PacketSummaryRepository authOffRepo;

    @BeforeEach
    void seed() {
        clean();
        authOnRepo = new PacketSummaryRepository(neo4j, new PacketReadPolicy(packetRepository, true));
        authOffRepo = new PacketSummaryRepository(neo4j, new PacketReadPolicy(packetRepository, false));

        neo4j.query("""
                CREATE (diff:Difficulty {id: $diffId, name: 'q5sum Hard'})

                CREATE (a1:Packet {id: 'q5sum-d-a-1', name: 'q5sum Alpha One', visibility: 'DRAFT',
                                    ownerId: $ownerA, version: 5})
                CREATE (a1t1:Tossup {id: 'q5sum-d-a-1-t1'})
                CREATE (a1t2:Tossup {id: 'q5sum-d-a-1-t2'})
                CREATE (a1)-[:CONTAINS_TOSSUP {order: 0}]->(a1t1)
                CREATE (a1)-[:CONTAINS_TOSSUP {order: 1}]->(a1t2)
                CREATE (a1b1:Bonus {id: 'q5sum-d-a-1-b1'})
                CREATE (a1)-[:CONTAINS_BONUS {order: 0}]->(a1b1)
                CREATE (a1bp1:BonusPart {id: 'q5sum-d-a-1-bp1'})
                CREATE (a1bp2:BonusPart {id: 'q5sum-d-a-1-bp2'})
                CREATE (a1bp3:BonusPart {id: 'q5sum-d-a-1-bp3'})
                CREATE (a1b1)-[:HAS_PART {order: 0}]->(a1bp1)
                CREATE (a1b1)-[:HAS_PART {order: 1}]->(a1bp2)
                CREATE (a1b1)-[:HAS_PART {order: 2}]->(a1bp3)

                CREATE (:Packet {id: 'q5sum-d-a-2', name: 'q5sum Alpha Two (empty)', visibility: 'DRAFT',
                                 ownerId: $ownerA})

                CREATE (b1:Packet {id: 'q5sum-d-b-1', name: 'q5sum Beta One', visibility: 'DRAFT', ownerId: $ownerB})
                CREATE (b1t1:Tossup {id: 'q5sum-d-b-1-t1'})
                CREATE (b1)-[:CONTAINS_TOSSUP {order: 0}]->(b1t1)
                CREATE (b1b1:Bonus {id: 'q5sum-d-b-1-b1'})
                CREATE (b1)-[:CONTAINS_BONUS {order: 0}]->(b1b1)

                CREATE (pa1:Packet {id: 'q5sum-p-a-1', name: 'q5sum Alpha Published', visibility: 'PUBLISHED',
                                     ownerId: $ownerA})
                CREATE (pa1)-[:DIFFICULTY_LEVEL]->(diff)
                CREATE (pa1t1:Tossup {id: 'q5sum-p-a-1-t1'})
                CREATE (pa1t2:Tossup {id: 'q5sum-p-a-1-t2'})
                CREATE (pa1t3:Tossup {id: 'q5sum-p-a-1-t3'})
                CREATE (pa1)-[:CONTAINS_TOSSUP {order: 0}]->(pa1t1)
                CREATE (pa1)-[:CONTAINS_TOSSUP {order: 1}]->(pa1t2)
                CREATE (pa1)-[:CONTAINS_TOSSUP {order: 2}]->(pa1t3)

                CREATE (pb1:Packet {id: 'q5sum-p-b-1', name: 'q5sum Beta Published', visibility: 'PUBLISHED',
                                     ownerId: $ownerB})
                CREATE (pb1t1:Tossup {id: 'q5sum-p-b-1-t1'})
                CREATE (pb1)-[:CONTAINS_TOSSUP {order: 0}]->(pb1t1)

                CREATE (pn1:Packet {id: 'q5sum-p-none-1', name: 'q5sum Legacy Published', visibility: 'PUBLISHED'})
                CREATE (pn1)-[:DIFFICULTY_LEVEL]->(diff)
                CREATE (pn1t1:Tossup {id: 'q5sum-p-none-1-t1'})
                CREATE (pn1t2:Tossup {id: 'q5sum-p-none-1-t2'})
                CREATE (pn1)-[:CONTAINS_TOSSUP {order: 0}]->(pn1t1)
                CREATE (pn1)-[:CONTAINS_TOSSUP {order: 1}]->(pn1t2)

                CREATE (eph1:Packet {id: 'q5sum-eph-1', name: 'q5sum Ephemeral', visibility: 'EPHEMERAL'})
                CREATE (eph1t1:Tossup {id: 'q5sum-eph-1-t1'})
                CREATE (eph1)-[:CONTAINS_TOSSUP {order: 0}]->(eph1t1)
                """)
                .bindAll(java.util.Map.of("diffId", DIFF_ID, "ownerA", OWNER_A, "ownerB", OWNER_B))
                .run();
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        neo4j.query("""
                MATCH (n) WHERE n.id STARTS WITH $prefix OR n.name STARTS WITH $namePrefix
                DETACH DELETE n
                """).bind(PREFIX).to("prefix").bind(NAME_PREFIX).to("namePrefix").run();
    }

    /** Every non-EPHEMERAL id, in {@code toLower(name), id} order (matches the query's ORDER BY). */
    private static final List<String> ALL_LISTED_IN_NAME_ORDER = List.of(
            "q5sum-d-a-1", "q5sum-p-a-1", "q5sum-d-a-2", "q5sum-d-b-1", "q5sum-p-b-1", "q5sum-p-none-1");

    @Test
    void guestSeesPublishedOnly() {
        PacketPageDto page = queryAs(authOnRepo, anonymous(), filter(null, "q5sum", null, null, null), 0, 100);

        assertThat(ids(page)).containsExactlyInAnyOrder("q5sum-p-a-1", "q5sum-p-b-1", "q5sum-p-none-1");
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void ownerSeesOwnDraftsPlusPublished() {
        PacketPageDto page = queryAs(authOnRepo, jwt(OWNER_A, "packet:create", "packet:update"),
                filter(null, "q5sum", null, null, null), 0, 100);

        assertThat(ids(page)).containsExactlyInAnyOrder(
                "q5sum-d-a-1", "q5sum-d-a-2", "q5sum-p-a-1", "q5sum-p-b-1", "q5sum-p-none-1");
        assertThat(page.total()).isEqualTo(5);
    }

    @Test
    void manageAnySeesEverythingExceptEphemeral() {
        PacketPageDto page = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "q5sum", null, null, null), 0, 100);

        assertThat(ids(page)).containsExactlyInAnyOrderElementsOf(ALL_LISTED_IN_NAME_ORDER);
        assertThat(page.total()).isEqualTo(6);
    }

    @Test
    void authDisabledShowsEverythingExceptEphemeral() {
        // Even an anonymous caller sees every listed packet when auth is off.
        PacketPageDto page = queryAs(authOffRepo, anonymous(), filter(null, "q5sum", null, null, null), 0, 100);

        assertThat(ids(page)).containsExactlyInAnyOrderElementsOf(ALL_LISTED_IN_NAME_ORDER);
        assertThat(page.total()).isEqualTo(6);
    }

    @Test
    void mineFiltersToTheCallersOwnPackets() {
        PacketPageDto page = queryAs(authOnRepo, jwt(OWNER_A, "packet:create"),
                filter(true, "q5sum", null, null, null), 0, 100);

        assertThat(ids(page)).containsExactlyInAnyOrder("q5sum-d-a-1", "q5sum-d-a-2", "q5sum-p-a-1");
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void mineWithoutAnAuthenticatedCallerIsEmptyNotAnError() {
        PacketPageDto page = queryAs(authOnRepo, anonymous(), filter(true, null, null, null, null), 3, 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(10);
    }

    @Test
    void nameContainsIsCaseInsensitive() {
        PacketPageDto page = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "ALPHA", null, null, null), 0, 100);

        assertThat(ids(page)).containsExactlyInAnyOrder("q5sum-d-a-1", "q5sum-d-a-2", "q5sum-p-a-1");
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void difficultyIdFiltersToAttachedPackets() {
        PacketPageDto page = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "q5sum", DIFF_ID, null, null), 0, 100);

        assertThat(ids(page)).containsExactlyInAnyOrder("q5sum-p-a-1", "q5sum-p-none-1");
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void visibilityFilterIsIntersectedWithReadRights() {
        // Owner A filters for DRAFT: only their own drafts, never owner B's.
        PacketPageDto page = queryAs(authOnRepo, jwt(OWNER_A, "packet:create"),
                filter(null, "q5sum", null, PacketVisibility.DRAFT, null), 0, 100);

        assertThat(ids(page)).containsExactlyInAnyOrder("q5sum-d-a-1", "q5sum-d-a-2");
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void ephemeralVisibilityFilterIsAlwaysEmptyEvenForManageAny() {
        PacketPageDto page = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "q5sum", null, PacketVisibility.EPHEMERAL, null), 0, 100);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }

    @Test
    void playableOnlyExcludesUnplayablePackets() {
        PacketPageDto page = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "q5sum", null, null, true), 0, 100);

        // Excluded: d-a-2 (no tossups, NO_TOSSUPS) and d-b-1 (its one bonus has no parts,
        // BONUS_WITHOUT_PARTS) -- the same two ERROR rules PacketValidator uses.
        assertThat(ids(page)).containsExactlyInAnyOrder("q5sum-d-a-1", "q5sum-p-a-1", "q5sum-p-b-1", "q5sum-p-none-1");
        assertThat(page.total()).isEqualTo(4);
    }

    @Test
    void playableAgreesWithPacketValidatorForEveryFixturePacket() {
        PacketPageDto page = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "q5sum", null, null, null), 0, 100);

        for (PacketSummaryDto summary : page.items()) {
            Packet full = packetRepository.findById(summary.id()).orElseThrow();
            boolean expected = validator.validate(full).playable();
            assertThat(summary.playable()).as(summary.id()).isEqualTo(expected);
            assertThat(summary.tossupCount()).as(summary.id()).isEqualTo(validator.validate(full).tossupCount());
            assertThat(summary.bonusCount()).as(summary.id()).isEqualTo(validator.validate(full).bonusCount());
        }
    }

    @Test
    void paginationSplitsResultsBySizeAndKeepsTheTotalStable() {
        List<String> collected = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            PacketPageDto dto = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                    filter(null, "q5sum", null, null, null), page, 2);
            assertThat(dto.total()).isEqualTo(6);
            assertThat(dto.size()).isEqualTo(2);
            assertThat(dto.page()).isEqualTo(page);
            assertThat(dto.items()).hasSize(2);
            collected.addAll(ids(dto));
        }
        assertThat(collected).containsExactlyElementsOf(ALL_LISTED_IN_NAME_ORDER);
    }

    @Test
    void resultsAreSortedByNameThenId() {
        PacketPageDto page = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "q5sum", null, null, null), 0, 100);

        assertThat(ids(page)).containsExactlyElementsOf(ALL_LISTED_IN_NAME_ORDER);
    }

    @Test
    void sizeIsCappedAtOneHundred() {
        PacketPageDto page = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "q5sum", null, null, null), 0, 500);

        assertThat(page.size()).isEqualTo(100);
        assertThat(page.items()).hasSize(6);
        assertThat(page.total()).isEqualTo(6);
    }

    @Test
    void nonPositiveOrMissingSizeFallsBackToTwentyFive() {
        PacketPageDto zero = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "q5sum", null, null, null), 0, 0);
        PacketPageDto missing = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "q5sum", null, null, null), null, null);

        assertThat(zero.size()).isEqualTo(25);
        assertThat(missing.size()).isEqualTo(25);
        assertThat(missing.page()).isZero();
    }

    @Test
    void summaryFieldsRoundTripOwnerDifficultyAndVersion() {
        PacketPageDto page = queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"),
                filter(null, "q5sum", null, null, null), 0, 100);
        PacketSummaryDto ownerAOwnDraft = byId(page, "q5sum-d-a-1");
        PacketSummaryDto ownerlessPublished = byId(page, "q5sum-p-none-1");
        PacketSummaryDto publishedWithDifficulty = byId(page, "q5sum-p-a-1");

        assertThat(ownerAOwnDraft.owner().id()).isEqualTo(OWNER_A);
        assertThat(ownerAOwnDraft.version()).isEqualTo(5);
        assertThat(ownerAOwnDraft.difficulty()).isNull();

        assertThat(ownerlessPublished.owner()).isNull();
        assertThat(ownerlessPublished.version()).isEqualTo(0); // never set: null reads as 0

        assertThat(publishedWithDifficulty.difficulty().getId()).isEqualTo(DIFF_ID);
        assertThat(publishedWithDifficulty.difficulty().getName()).isEqualTo("q5sum Hard");
    }

    /**
     * Q-M3V1-01: {@code owner.id} is the author's Keycloak subject, so the list redacts it
     * with the same rule as {@code Packet.owner} (Q-M2-01): only the owner, a caller who may
     * read every packet ({@code packet:manage-any}/{@code packet:read-answers}), or anyone
     * with auth off gets the id. Everyone else still gets the display name.
     */
    @Test
    void ownerIdRedactedForAnonymousAndOtherAuthorsButNotOwnerAdminOrAuthOff() {
        neo4j.query("MATCH (p:Packet {id: 'q5sum-p-a-1'}) SET p.ownerDisplayName = 'Alice A'").run();
        PacketFilterInput all = filter(null, "q5sum", null, null, null);

        PacketSummaryDto asAnonymous = byId(queryAs(authOnRepo, anonymous(), all, 0, 100), "q5sum-p-a-1");
        PacketSummaryDto asOtherAuthor = byId(queryAs(authOnRepo, jwt(OWNER_B, "packet:create"), all, 0, 100),
                "q5sum-p-a-1");
        PacketSummaryDto asOwner = byId(queryAs(authOnRepo, jwt(OWNER_A, "packet:create"), all, 0, 100),
                "q5sum-p-a-1");
        PacketSummaryDto asManageAny = byId(queryAs(authOnRepo, jwt("q5sum-admin", "packet:manage-any"), all, 0, 100),
                "q5sum-p-a-1");
        PacketSummaryDto authOff = byId(queryAs(authOffRepo, anonymous(), all, 0, 100), "q5sum-p-a-1");

        assertThat(asAnonymous.owner().id()).isNull();
        assertThat(asAnonymous.owner().name()).isEqualTo("Alice A");
        assertThat(asOtherAuthor.owner().id()).isNull();
        assertThat(asOtherAuthor.owner().name()).isEqualTo("Alice A");
        // The other author's own row keeps its id, so ng's canManage comparison still works.
        assertThat(byId(queryAs(authOnRepo, jwt(OWNER_B, "packet:create"), all, 0, 100), "q5sum-p-b-1")
                .owner().id()).isEqualTo(OWNER_B);

        assertThat(asOwner.owner().id()).isEqualTo(OWNER_A);
        assertThat(asManageAny.owner().id()).isEqualTo(OWNER_A);
        assertThat(authOff.owner().id()).isEqualTo(OWNER_A);
        assertThat(authOff.owner().name()).isEqualTo("Alice A");
    }

    /* --------------------------------- helpers -------------------------------- */

    private static PacketFilterInput filter(Boolean mine, String nameContains, String difficultyId,
                                            PacketVisibility visibility, Boolean playableOnly) {
        return new PacketFilterInput(mine, nameContains, difficultyId, visibility, playableOnly);
    }

    private static PacketPageDto queryAs(PacketSummaryRepository repo, Authentication auth,
                                        PacketFilterInput filter, Integer page, Integer size) {
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return repo.find(filter, page, size);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static List<String> ids(PacketPageDto page) {
        return page.items().stream().map(PacketSummaryDto::id).toList();
    }

    private static PacketSummaryDto byId(PacketPageDto page, String id) {
        return page.items().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + id));
    }

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }

    private static Authentication jwt(String sub, String... authorities) {
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList(), sub);
    }
}

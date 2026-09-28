package com.soulsoftworks.sockbowlquestions.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * WP-Q4 acceptance (the questions endpoint of M4-AD-01): the contract game's
 * {@code QuestionsUsageClient} (WP-G6) calls, {@code GET
 * /api/admin/usage/content-counts?subs=a,b,...} answering
 * {@code {sub: {packetsOwned, questionsCreated}}}, through the real auth-on
 * security chain and a real Neo4j.
 */
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs"
})
@AutoConfigureMockMvc
class AdminUsageControllerTest extends Neo4jContainerTestBase {

    static final String URL = "/api/admin/usage/content-counts";
    static final String ALICE = "q4counts-alice";
    static final String BOB = "q4counts-bob";
    static final String NOBODY = "q4counts-nobody";

    private final Gson gson = new Gson();

    @Autowired
    MockMvc mvc;

    @Autowired
    Neo4jClient neo4j;

    @MockitoBean
    JwtDecoder jwtDecoder;

    static RequestPostProcessor user(String sub, String... roles) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("azp", "sockbowl-game")
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(Arrays.stream(roles).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    static RequestPostProcessor admin() {
        return user("q4counts-admin", "admin", "admin:access", "user:ban");
    }

    @BeforeEach
    void seed() {
        clean();
        // Alice: 2 packets; created 2 tossups and 1 bonus (its part isn't counted), one of
        // them in Bob's packet. Bob: 1 packet, 1 tossup. A legacy (pre-M4) tossup in
        // Alice's packet has no createdBy and counts for nobody.
        neo4j.query("""
                CREATE (p1:Packet {id: 'q4counts-p1', name: 'q4counts-p1', ownerId: $alice})
                CREATE (p2:Packet {id: 'q4counts-p2', name: 'q4counts-p2', ownerId: $alice})
                CREATE (p3:Packet {id: 'q4counts-p3', name: 'q4counts-p3', ownerId: $bob})
                CREATE (p1)-[:CONTAINS_TOSSUP {order: 0}]->(:Tossup {id: 'q4counts-t1', createdBy: $alice})
                CREATE (p1)-[:CONTAINS_TOSSUP {order: 1}]->(:Tossup {id: 'q4counts-t2'})
                CREATE (p3)-[:CONTAINS_TOSSUP {order: 0}]->(:Tossup {id: 'q4counts-t3', createdBy: $alice})
                CREATE (p3)-[:CONTAINS_TOSSUP {order: 1}]->(:Tossup {id: 'q4counts-t4', createdBy: $bob})
                CREATE (p2)-[:CONTAINS_BONUS {order: 0}]->(b:Bonus {id: 'q4counts-b1', createdBy: $alice})
                CREATE (b)-[:HAS_PART {order: 0}]->(:BonusPart {id: 'q4counts-bp1', createdBy: $alice})
                """).bindAll(Map.of("alice", ALICE, "bob", BOB)).run();
    }

    @AfterEach
    void clean() {
        neo4j.query("MATCH (n) WHERE n.id STARTS WITH 'q4counts-' DETACH DELETE n").run();
    }

    JsonObject json(MvcResult result) throws Exception {
        return gson.fromJson(result.getResponse().getContentAsString(), JsonObject.class);
    }

    @Test
    void adminGetsCorrectCountsForEveryRequestedSubject() throws Exception {
        MvcResult result = mvc.perform(get(URL).param("subs", ALICE + "," + BOB + "," + NOBODY).with(admin()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonObject body = json(result);
        assertThat(body.keySet()).containsExactlyInAnyOrder(ALICE, BOB, NOBODY);
        assertThat(body.getAsJsonObject(ALICE).get("packetsOwned").getAsLong()).isEqualTo(2);
        assertThat(body.getAsJsonObject(ALICE).get("questionsCreated").getAsLong()).isEqualTo(3);
        assertThat(body.getAsJsonObject(BOB).get("packetsOwned").getAsLong()).isEqualTo(1);
        assertThat(body.getAsJsonObject(BOB).get("questionsCreated").getAsLong()).isEqualTo(1);
        assertThat(body.getAsJsonObject(NOBODY).get("packetsOwned").getAsLong()).isZero();
        assertThat(body.getAsJsonObject(NOBODY).get("questionsCreated").getAsLong()).isZero();
    }

    @Test
    void blanksAndDuplicatesAreIgnoredAndNoSubjectsIsAnEmptyObject() throws Exception {
        JsonObject body = json(mvc.perform(get(URL).param("subs", " " + BOB + ",," + BOB + " ,")
                .with(admin())).andReturn());
        assertThat(body.keySet()).containsExactly(BOB);

        MvcResult none = mvc.perform(get(URL).with(admin())).andReturn();
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(none).keySet()).isEmpty();
    }

    @Test
    void aPlayerOrAuthorIsForbiddenAndAnonymousIsUnauthorized() throws Exception {
        assertThat(mvc.perform(get(URL).param("subs", ALICE).with(user("q4counts-p", "player", "packet:read")))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(URL).param("subs", ALICE)
                        .with(user("q4counts-a", "author", "packet:read", "packet:create", "packet:manage-any")))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(URL).param("subs", ALICE)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void moreThanOneHundredSubjectsIsA400AndExactlyOneHundredIsFine() throws Exception {
        String hundred = IntStream.rangeClosed(1, 100).mapToObj(i -> "q4counts-s" + i)
                .collect(Collectors.joining(","));
        MvcResult ok = mvc.perform(get(URL).param("subs", hundred).with(admin())).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(ok).keySet()).hasSize(100);

        MvcResult tooMany = mvc.perform(get(URL).param("subs", hundred + ",q4counts-s101").with(admin()))
                .andReturn();
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooMany.getResponse().getContentAsString()).contains("100");
    }

    @Test
    void onlyGetIsServed() throws Exception {
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(URL).param("subs", ALICE).with(admin()))
                .andReturn().getResponse().getStatus()).isIn(403, 405);
    }
}

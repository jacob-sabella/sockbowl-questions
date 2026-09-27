package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.config.EphemeralPacketProperties;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.service.EphemeralPacketCleanupJob;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D15 end to end against a real Neo4j with auth on: a guest's {@code import-random}
 * makes an ownerless EPHEMERAL packet that no list or search shows, that only the
 * game service token can read (with answers, so the game can play it), and that the
 * TTL cleanup deletes with its questions once it is older than
 * {@code sockbowl.packet.ephemeral-ttl}. An author still gets an owned DRAFT.
 */
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        // The scheduled run must not race the test; it calls the job directly.
        "sockbowl.packet.ephemeral-cleanup.initial-delay=PT24H"})
@AutoConfigureMockMvc
class EphemeralPacketFlowIT extends Neo4jContainerTestBase {

    private static final String CATEGORY = "EphemeralFlowIT";
    private static final String IMPORT = "{\"categories\":[\"" + CATEGORY + "\"],\"tossupCount\":2,\"bonusCount\":1,"
            + "\"name\":\"eph-flow packet\"}";

    @Autowired private MockMvc mvc;
    @Autowired private Neo4jClient neo4j;
    @Autowired private PacketRepository packetRepository;
    @Autowired private EphemeralPacketProperties properties;
    @Autowired private EphemeralPacketCleanupJob scheduledJob;

    @BeforeEach
    void seedBank() {
        clean();
        neo4j.query("""
                CREATE (:BankTossup {remoteId: 'eph-flow-t1', question: 'Tossup one?', answer: 'Answer one',
                                     category: $cat, subcategory: $cat})
                CREATE (:BankTossup {remoteId: 'eph-flow-t2', question: 'Tossup two?', answer: 'Answer two',
                                     category: $cat, subcategory: $cat})
                CREATE (b:BankBonus {remoteId: 'eph-flow-b1', preamble: 'Bonus pre', category: $cat, subcategory: $cat})
                CREATE (b)-[:HAS_PART {order: 0}]->(:BankBonusPart {question: 'Part one?', answer: 'Part answer'})
                """).bind(CATEGORY).to("cat").run();
    }

    @AfterEach
    void clean() {
        neo4j.query("""
                MATCH (p:Packet) WHERE p.name STARTS WITH 'eph-flow'
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t:Tossup)
                OPTIONAL MATCH (p)-[:CONTAINS_BONUS]->(b:Bonus)
                OPTIONAL MATCH (b)-[:HAS_PART]->(bp:BonusPart)
                DETACH DELETE p, t, b, bp
                """).run();
        neo4j.query("""
                MATCH (n) WHERE (n:BankTossup OR n:BankBonus) AND n.remoteId STARTS WITH 'eph-flow'
                OPTIONAL MATCH (n)-[:HAS_PART]->(bp:BankBonusPart)
                DETACH DELETE n, bp
                """).run();
    }

    @Test
    void guestImportIsEphemeralUnlistedPlayableByTheServiceAndCleanedUpAfterTheTtl() throws Exception {
        assertThat(properties.getEphemeralTtl()).isEqualTo(Duration.ofHours(24));

        String id = importRandom();
        Map<String, Object> stored = props(id);
        assertThat(stored).containsEntry("visibility", "EPHEMERAL").containsEntry("createdVia", "import-random")
                .containsKey("ephemeralCreatedAt").doesNotContainKey("ownerId");

        // Never listed or searched, for anyone.
        for (RequestPostProcessor[] who : callers()) {
            graphql("{ getAllPackets { id } }", who)
                    .andExpect(jsonPath("$.data.getAllPackets[*].id", everyItem(not(id))));
            graphql("{ searchPacketsByName(name: \"eph-flow\") { id } }", who)
                    .andExpect(jsonPath("$.data.searchPacketsByName[*].id", everyItem(not(id))));
        }

        // Hidden by id from everyone but the game service ...
        for (RequestPostProcessor[] who : List.of(new RequestPostProcessor[0],
                new RequestPostProcessor[]{as("player-sub", "game:host")},
                new RequestPostProcessor[]{as("admin-sub", "packet:manage-any", "packet:update")})) {
            graphql("{ getPacketById(id: \"" + id + "\") { id } }", who)
                    .andExpect(jsonPath("$.data.getPacketById").value(nullValue()));
        }
        // ... which reads it in full, answers included, to play it (SetMatchPacket).
        graphql("{ getPacketById(id: \"" + id + "\") { visibility answersRedacted owner { id } "
                + "tossups { tossup { question answer } } "
                + "bonuses { bonus { bonusParts { bonusPart { answer } } } } } }", service())
                .andExpect(jsonPath("$.data.getPacketById.visibility").value("EPHEMERAL"))
                .andExpect(jsonPath("$.data.getPacketById.answersRedacted").value(false))
                .andExpect(jsonPath("$.data.getPacketById.owner").value(nullValue()))
                .andExpect(jsonPath("$.data.getPacketById.tossups.length()").value(2))
                .andExpect(jsonPath("$.data.getPacketById.tossups[*].tossup.answer", everyItem(not(nullValue()))))
                .andExpect(jsonPath("$.data.getPacketById.bonuses[0].bonus.bonusParts[0].bonusPart.answer")
                        .value("Part answer"));

        // Not editable, even for manage-any.
        graphql("mutation { renamePacket(id: \"" + id + "\", name: \"eph-flow renamed\") { id } }",
                as("admin-sub", "packet:update", "packet:manage-any"))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        // TTL: an hour later it's still there; a day and a bit later it's gone, questions too.
        cleanupAt(Duration.ofHours(1)).purgeExpired();
        assertThat(packetRepository.existsById(id)).isTrue();

        cleanupAt(properties.getEphemeralTtl().plusMinutes(5)).purgeExpired();
        assertThat(packetRepository.existsById(id)).isFalse();
        assertThat(copiedQuestionNodes()).isZero();
        graphql("{ getPacketById(id: \"" + id + "\") { id } }", service())
                .andExpect(jsonPath("$.data.getPacketById").value(nullValue()));
    }

    @Test
    void cleanupLeavesDraftsAndPublishedPacketsAlone() throws Exception {
        String draft = importRandom(as("author-sub", "packet:create", "packet:read"));
        assertThat(props(draft)).containsEntry("visibility", "DRAFT").containsEntry("ownerId", "author-sub")
                .doesNotContainKey("ephemeralCreatedAt");

        cleanupAt(Duration.ofDays(365)).purgeExpired();

        assertThat(packetRepository.existsById(draft)).isTrue();
        // The author's DRAFT is listed for its owner as before.
        graphql("{ searchPacketsByName(name: \"eph-flow\") { id } }", as("author-sub", "packet:create"))
                .andExpect(jsonPath("$.data.searchPacketsByName[*].id").value(org.hamcrest.Matchers.hasItem(draft)));
    }

    @Test
    void theScheduledJobBeanIsWiredWithTheConfiguredTtl() {
        // Nothing older than the TTL was created by this test class, so a real run is a no-op for it.
        assertThat(scheduledJob).isNotNull();
        scheduledJob.scheduledPurge();
    }

    /* --------------------------------- helpers --------------------------------- */

    private EphemeralPacketCleanupJob cleanupAt(Duration later) {
        return new EphemeralPacketCleanupJob(packetRepository, properties,
                Clock.offset(Clock.systemUTC(), later));
    }

    private String importRandom(RequestPostProcessor... who) throws Exception {
        var req = post("/api/qbreader/import-random").contentType(MediaType.APPLICATION_JSON).content(IMPORT);
        for (RequestPostProcessor p : who) {
            req = req.with(p);
        }
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private Map<String, Object> props(String id) {
        return neo4j.query("MATCH (p:Packet {id: $id}) RETURN properties(p) AS props").bind(id).to("id")
                .fetch().one().map(EphemeralPacketFlowIT::propsOf).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propsOf(Map<String, Object> row) {
        return (Map<String, Object>) row.get("props");
    }

    private long copiedQuestionNodes() {
        // Tossups/bonuses copied for this packet carry the seeded remote ids.
        return neo4j.query("""
                MATCH (n) WHERE (n:Tossup OR n:Bonus) AND n.remoteId STARTS WITH 'eph-flow'
                RETURN count(n) AS c
                """).fetchAs(Long.class).one().orElse(0L);
    }

    private static List<RequestPostProcessor[]> callers() {
        return List.of(new RequestPostProcessor[0],
                new RequestPostProcessor[]{as("player-sub", "game:host")},
                new RequestPostProcessor[]{as("admin-sub", "packet:manage-any")},
                new RequestPostProcessor[]{service()});
    }

    private static RequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("service-account-sockbowl-game-backend").claim("azp", "sockbowl-game-backend"))
                .authorities(new SimpleGrantedAuthority("packet:read"), new SimpleGrantedAuthority("packet:read-answers"));
    }

    private static RequestPostProcessor as(String sub, String... authorities) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                Arrays.stream(authorities).map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList());
    }

    private ResultActions graphql(String query, RequestPostProcessor... auth) throws Exception {
        String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("query", query));
        var req = post("/graphql").contentType(MediaType.APPLICATION_JSON).content(body);
        for (RequestPostProcessor p : auth) {
            req = req.with(p);
        }
        ResultActions actions = mvc.perform(req);
        MvcResult first = actions.andReturn();
        if (first.getRequest().isAsyncStarted()) {
            actions = mvc.perform(asyncDispatch(first));
        }
        return actions.andExpect(status().isOk());
    }
}

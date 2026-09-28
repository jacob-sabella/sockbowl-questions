package com.soulsoftworks.sockbowlquestions.api;

import com.jayway.jsonpath.JsonPath;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Q3-01: {@code import-random}'s unique-name suffix must never reveal that another
 * caller's private (DRAFT) packet exists. Before the fix, {@code QbreaderImportService
 * #findByExactName} searched every <em>listed</em> packet regardless of who owns it
 * or its visibility, so a second author importing the exact name of someone else's
 * DRAFT silently got back {@code "<name> (2)"} — proof, with no read access, that a
 * packet with that name exists. This class fails on that behavior and passes once the
 * name check is scoped to what the caller may see (their own packets, plus anything
 * publicly readable).
 */
@SpringBootTest(properties = "sockbowl.auth.enabled=true")
@AutoConfigureMockMvc
class QbreaderImportUniqueNameLeakIT extends Neo4jContainerTestBase {

    private static final String CATEGORY = "Q3UniqueNameLeakIT";
    private static final String AUTHOR1_SUB = "q3-leak-author1";
    private static final String AUTHOR2_SUB = "q3-leak-author2";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private Neo4jClient neo4j;
    @Autowired
    private PacketRepository packetRepository;

    @BeforeEach
    void seedBank() {
        clean();
        // Two independent bank tossups so each import samples a fresh one and the
        // packets built by the two authors are otherwise unrelated.
        neo4j.query("""
                CREATE (:BankTossup {remoteId: 'q3-leak-t1', question: 'Tossup one?', answer: 'Answer one',
                                     category: $cat, subcategory: $cat})
                CREATE (:BankTossup {remoteId: 'q3-leak-t2', question: 'Tossup two?', answer: 'Answer two',
                                     category: $cat, subcategory: $cat})
                """).bind(CATEGORY).to("cat").run();
    }

    @AfterEach
    void clean() {
        neo4j.query("""
                MATCH (p:Packet) WHERE p.name STARTS WITH 'q3-leak'
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t)
                DETACH DELETE p, t
                """).run();
        neo4j.query("MATCH (n:BankTossup) WHERE n.remoteId STARTS WITH 'q3-leak' DETACH DELETE n").run();
    }

    @Test
    void nonOwnerImportingAnotherAuthorsDraftNameGetsTheExactNameBack() throws Exception {
        String name = "q3-leak-" + UUID.randomUUID();

        // author1 creates the DRAFT that reserves the name.
        String author1PacketId = importRandom(name, author(AUTHOR1_SUB));
        assertThat(packetRepository.findById(author1PacketId)).isPresent();

        // author2 cannot see author1's DRAFT (not the owner, no manage-any), so their
        // import must NOT be told the name is taken: no "(2)" suffix.
        String author2PacketName = importRandomAndGetName(name, author(AUTHOR2_SUB));
        assertThat(author2PacketName)
                .as("a non-owner's import-random name must not leak that another private packet has this name")
                .isEqualTo(name);

        // Sanity check the mechanism is not simply disabled: author1 importing the SAME
        // name again (their own packet is visible to them) still gets de-duplicated.
        String author1SecondPacketName = importRandomAndGetName(name, author(AUTHOR1_SUB));
        assertThat(author1SecondPacketName).isEqualTo(name + " (2)");
    }

    @Test
    void publishedPacketNameIsStillScopedForEveryone() throws Exception {
        String name = "q3-leak-pub-" + UUID.randomUUID();

        String author1PacketId = importRandom(name, author(AUTHOR1_SUB));
        publish(author1PacketId, AUTHOR1_SUB);

        // Once PUBLISHED, the packet is publicly readable, so the name collision is real
        // information (anyone could find it via search) and the suffix is expected.
        String author2PacketName = importRandomAndGetName(name, author(AUTHOR2_SUB));
        assertThat(author2PacketName).isEqualTo(name + " (2)");
    }

    /* --------------------------------- helpers --------------------------------- */

    private String importRandom(String name, RequestPostProcessor who) throws Exception {
        return JsonPath.read(importRandomBody(name, who), "$.id");
    }

    private String importRandomAndGetName(String name, RequestPostProcessor who) throws Exception {
        return JsonPath.read(importRandomBody(name, who), "$.name");
    }

    private String importRandomBody(String name, RequestPostProcessor who) throws Exception {
        String body = "{\"categories\":[\"" + CATEGORY + "\"],\"tossupCount\":1,\"bonusCount\":0,\"name\":\""
                + name + "\"}";
        MockHttpServletRequestBuilder req = post("/api/qbreader/import-random")
                .with(who).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void publish(String packetId, String ownerSub) throws Exception {
        String query = "mutation { setPacketVisibility(id: \"" + packetId + "\", visibility: PUBLISHED) { id } }";
        String body = new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(java.util.Map.of("query", query));
        mvc.perform(post("/graphql").with(author(ownerSub, "packet:create", "packet:update"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor author(String sub) {
        return author(sub, "packet:create");
    }

    private static RequestPostProcessor author(String sub, String... authorities) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                Arrays.stream(authorities).map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList());
    }
}

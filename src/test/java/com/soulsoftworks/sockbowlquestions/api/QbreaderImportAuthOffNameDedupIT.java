package com.soulsoftworks.sockbowlquestions.api;

import com.jayway.jsonpath.JsonPath;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Q4-01: with {@code sockbowl.auth.enabled=false} every {@code import-random} packet
 * is an ownerless DRAFT, so {@code QbreaderImportService#findByExactName}'s owner-scoped
 * search (added for Q3-01, which never matches when there is no owner and DRAFT is not
 * publicly readable) silently stopped de-duplicating names. This class fails on that
 * regression and passes once auth-off falls back to the owner-blind
 * {@code PacketRepository#searchListedByName}: importing the same name twice must get
 * back {@code "<name>"} then {@code "<name> (2)"}, exactly as before Q3-01.
 */
@SpringBootTest(properties = "sockbowl.auth.enabled=false")
@AutoConfigureMockMvc
class QbreaderImportAuthOffNameDedupIT extends Neo4jContainerTestBase {

    private static final String CATEGORY = "Q4DedupIT";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private Neo4jClient neo4j;

    @BeforeEach
    void seedBank() {
        clean();
        neo4j.query("""
                CREATE (:BankTossup {remoteId: 'q4-dedup-t1', question: 'Tossup one?', answer: 'Answer one',
                                     category: $cat, subcategory: $cat})
                CREATE (:BankTossup {remoteId: 'q4-dedup-t2', question: 'Tossup two?', answer: 'Answer two',
                                     category: $cat, subcategory: $cat})
                """).bind(CATEGORY).to("cat").run();
    }

    @AfterEach
    void clean() {
        neo4j.query("""
                MATCH (p:Packet) WHERE p.name STARTS WITH 'q4-dedup'
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t)
                DETACH DELETE p, t
                """).run();
        neo4j.query("MATCH (n:BankTossup) WHERE n.remoteId STARTS WITH 'q4-dedup' DETACH DELETE n").run();
    }

    @Test
    void authOffImportRandomStillDedupesTheSameNameAcrossAnonymousCallers() throws Exception {
        String name = "q4-dedup-" + UUID.randomUUID();

        assertThat(importRandomAndGetName(name)).isEqualTo(name);
        assertThat(importRandomAndGetName(name)).isEqualTo(name + " (2)");
        assertThat(importRandomAndGetName(name)).isEqualTo(name + " (3)");
    }

    private String importRandomAndGetName(String name) throws Exception {
        String body = "{\"categories\":[\"" + CATEGORY + "\"],\"tossupCount\":1,\"bonusCount\":0,\"name\":\""
                + name + "\"}";
        String response = mvc.perform(post("/api/qbreader/import-random")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.name");
    }
}

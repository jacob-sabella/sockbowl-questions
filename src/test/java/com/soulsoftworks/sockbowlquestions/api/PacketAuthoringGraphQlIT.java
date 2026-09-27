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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 Q2 end to end through {@code /graphql} against a real Neo4j (auth off; the auth
 * matrix is Q6's): {@code Packet.version} and {@code expectedVersion} (PB-18), the
 * {@code CONFLICT} and {@code VALIDATION_FAILED} classifications with their extensions,
 * {@code Packet.validation} (PB-11), and clearing a subcategory (PB-09) actually deleting
 * the relationship in both directions.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PacketAuthoringGraphQlIT extends Neo4jContainerTestBase {

    private static final String PREFIX = "q2-gql-";

    @Autowired private MockMvc mvc;
    @Autowired private Neo4jClient neo4j;

    @BeforeEach
    void seedTaxonomy() {
        clean();
        neo4j.query("""
                CREATE (c:Category {id: 'q2-gql-cat', name: 'q2-gql Science'})
                CREATE (:Subcategory {id: 'q2-gql-sub', name: 'q2-gql Biology'})-[:SUBCATEGORY_OF]->(c)
                """).run();
    }

    @AfterEach
    void clean() {
        neo4j.query("""
                MATCH (p:Packet) WHERE p.name STARTS WITH $prefix
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t)
                OPTIONAL MATCH (p)-[:CONTAINS_BONUS]->(b)
                OPTIONAL MATCH (b)-[:HAS_PART]->(bp)
                DETACH DELETE p, t, b, bp
                """).bind(PREFIX).to("prefix").run();
        neo4j.query("MATCH (n) WHERE n.id STARTS WITH $prefix DETACH DELETE n").bind(PREFIX).to("prefix").run();
    }

    /* -------------------------------- version ------------------------------- */

    @Test
    void versionStartsAtZero_bumpsOnEachMutation_andStaleWritesConflict() throws Exception {
        String id = createPacket("versioned");
        graphql("{ getPacketById(id: \"" + id + "\") { version } }")
                .andExpect(jsonPath("$.data.getPacketById.version").value(0));

        graphql("mutation { renamePacket(id: \"" + id + "\", name: \"" + PREFIX + "renamed\", expectedVersion: 0) { version name } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.renamePacket.version").value(1));

        // A second tab still holding version 0.
        graphql("mutation { renamePacket(id: \"" + id + "\", name: \"" + PREFIX + "lost\", expectedVersion: 0) { version } }")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("CONFLICT"))
                .andExpect(jsonPath("$.errors[0].extensions.packetId").value(id))
                .andExpect(jsonPath("$.errors[0].extensions.currentVersion").value(1));
        graphql("{ getPacketById(id: \"" + id + "\") { name version } }")
                .andExpect(jsonPath("$.data.getPacketById.name").value(PREFIX + "renamed"))
                .andExpect(jsonPath("$.data.getPacketById.version").value(1));

        // No expectedVersion: no check (the game and older clients), still bumps.
        graphql("mutation { setPacketVisibility(id: \"" + id + "\", visibility: PUBLISHED) { version visibility } }")
                .andExpect(jsonPath("$.data.setPacketVisibility.version").value(2))
                .andExpect(jsonPath("$.data.setPacketVisibility.visibility").value("PUBLISHED"));
        graphql("mutation { setPacketVisibility(id: \"" + id + "\", visibility: DRAFT, expectedVersion: 1) { version } }")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("CONFLICT"));
    }

    @Test
    void nodeLevelMutationBumpsTheContainingPacket() throws Exception {
        String id = createPacket("node-level");
        String tossupId = addTossup(id, 0);
        // create 0, add tossup 1
        graphql("mutation { updateTossup(id: \"" + tossupId + "\", input: {question: \"Q2\", answer: \"A2\"}, expectedVersion: 1) { question } }")
                .andExpect(jsonPath("$.errors").doesNotExist());
        graphql("{ getPacketById(id: \"" + id + "\") { version } }")
                .andExpect(jsonPath("$.data.getPacketById.version").value(2));
        graphql("mutation { updateTossup(id: \"" + tossupId + "\", input: {question: \"Q3\", answer: \"A3\"}, expectedVersion: 1) { question } }")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("CONFLICT"))
                .andExpect(jsonPath("$.errors[0].extensions.currentVersion").value(2));
    }

    /* ------------------------------ validation ------------------------------ */

    @Test
    void overLongNameIsValidationFailedWithField() throws Exception {
        String longName = PREFIX + "x".repeat(300);
        graphql("mutation { createPacket(input: {name: \"" + longName + "\"}) { id } }")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].extensions.field").value("name"));
    }

    @Test
    void bonusWithoutPartsIsRejected_andTheLastPartCantBeRemoved() throws Exception {
        String id = createPacket("parts");
        graphql("mutation { addBonusToPacket(packetId: \"" + id + "\", input: {preamble: \"P\"}) { id } }")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].extensions.field").value("parts"));

        MvcResult added = graphql("mutation { addBonusToPacket(packetId: \"" + id
                + "\", input: {preamble: \"P\", parts: [{question: \"Q\", answer: \"A\"}]}) "
                + "{ bonuses { bonus { id bonusParts { bonusPart { id } } } } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn();
        String body = added.getResponse().getContentAsString();
        String bonusId = JsonPath.read(body, "$.data.addBonusToPacket.bonuses[0].bonus.id");
        String partId = JsonPath.read(body, "$.data.addBonusToPacket.bonuses[0].bonus.bonusParts[0].bonusPart.id");

        graphql("mutation { removeBonusPart(bonusId: \"" + bonusId + "\", bonusPartId: \"" + partId + "\") { id } }")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].extensions.field").value("parts"));
        Long parts = neo4j.query("MATCH (:Bonus {id: $id})-[:HAS_PART]->(bp:BonusPart) RETURN count(bp)")
                .bind(bonusId).to("id").fetchAs(Long.class).one().orElseThrow();
        assertThat(parts).isEqualTo(1L);
    }

    @Test
    void packetValidationField() throws Exception {
        String id = createPacket("validation");
        graphql("{ getPacketById(id: \"" + id + "\") { validation { playable tossupCount bonusCount issues { severity code } } } }")
                .andExpect(jsonPath("$.data.getPacketById.validation.playable").value(false))
                .andExpect(jsonPath("$.data.getPacketById.validation.tossupCount").value(0))
                .andExpect(jsonPath("$.data.getPacketById.validation.issues[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.data.getPacketById.validation.issues[0].code").value("NO_TOSSUPS"));

        addTossup(id, null);
        graphql("{ getPacketById(id: \"" + id + "\") { validation { playable tossupCount issues { severity code tossupId } } } }")
                .andExpect(jsonPath("$.data.getPacketById.validation.playable").value(true))
                .andExpect(jsonPath("$.data.getPacketById.validation.tossupCount").value(1))
                .andExpect(jsonPath("$.data.getPacketById.validation.issues[0].severity").value("INFO"))
                .andExpect(jsonPath("$.data.getPacketById.validation.issues[0].code").value("MISSING_SUBCATEGORY"));
    }

    /* --------------------------- subcategory clear --------------------------- */

    @Test
    void nullSubcategoryClearsTheRelationshipForTossupsAndBonuses() throws Exception {
        String id = createPacket("clear");
        String tossupId = addTossup(id, null);
        graphql("mutation { setTossupSubcategory(tossupId: \"" + tossupId + "\", subcategoryId: \"q2-gql-sub\") { subcategory { id } } }")
                .andExpect(jsonPath("$.data.setTossupSubcategory.subcategory.id").value("q2-gql-sub"));
        assertThat(subcategoryLinks(tossupId)).isEqualTo(1L);

        graphql("mutation { setTossupSubcategory(tossupId: \"" + tossupId + "\", subcategoryId: null) { id subcategory { id } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.setTossupSubcategory.subcategory").doesNotExist());
        assertThat(subcategoryLinks(tossupId)).isZero();

        MvcResult added = graphql("mutation { addBonusToPacket(packetId: \"" + id
                + "\", input: {preamble: \"P\", subcategoryId: \"q2-gql-sub\", parts: [{question: \"Q\", answer: \"A\"}]}) "
                + "{ bonuses { bonus { id } } } }").andReturn();
        String bonusId = JsonPath.read(added.getResponse().getContentAsString(),
                "$.data.addBonusToPacket.bonuses[0].bonus.id");
        assertThat(subcategoryLinks(bonusId)).isEqualTo(1L);

        graphql("mutation { setBonusSubcategory(bonusId: \"" + bonusId + "\", subcategoryId: null) { id subcategory { id } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.setBonusSubcategory.subcategory").doesNotExist());
        assertThat(subcategoryLinks(bonusId)).isZero();
        // The taxonomy itself is untouched.
        Long subs = neo4j.query("MATCH (s:Subcategory {id: 'q2-gql-sub'}) RETURN count(s)")
                .fetchAs(Long.class).one().orElseThrow();
        assertThat(subs).isEqualTo(1L);
    }

    /* -------------------------------- helpers -------------------------------- */

    private long subcategoryLinks(String nodeId) {
        return neo4j.query("MATCH (n {id: $id})-[r:SUBCATEGORY_IS]-(:Subcategory) RETURN count(r)")
                .bind(nodeId).to("id").fetchAs(Long.class).one().orElseThrow();
    }

    private String createPacket(String name) throws Exception {
        MvcResult result = graphql("mutation { createPacket(input: {name: \"" + PREFIX + name + "\"}) { id version } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPacket.version").value(0))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.createPacket.id");
    }

    private String addTossup(String packetId, Integer expectedVersion) throws Exception {
        String version = expectedVersion == null ? "" : ", expectedVersion: " + expectedVersion;
        MvcResult result = graphql("mutation { addTossupToPacket(packetId: \"" + packetId
                + "\", input: {question: \"Q\", answer: \"A\"}" + version + ") { tossups { tossup { id } } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.addTossupToPacket.tossups[0].tossup.id");
    }

    private ResultActions graphql(String query) throws Exception {
        String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("query", query));
        ResultActions actions = mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult first = actions.andReturn();
        if (first.getRequest().isAsyncStarted()) {
            actions = mvc.perform(asyncDispatch(first));
        }
        return actions.andExpect(status().isOk());
    }
}

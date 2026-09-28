package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport;
import com.soulsoftworks.sockbowlquestions.support.M3AuthFixtures;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * The auth-off counterpart of {@link M3GraphQlAuthorizationTest} (M3 Q6): with
 * {@code sockbowl.auth.enabled=false} (the self-hosted default) every M3 operation works
 * for an anonymous caller, exactly as before auth existed. Packets created this way are
 * ownerless DRAFTs. Two rules still hold: EPHEMERAL packets are never listed (D15), and
 * {@code expectedVersion} is still checked (it is a correctness rule, not an auth rule).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "sockbowl.auth.enabled=false")
@AutoConfigureMockMvc
class M3GraphQlAuthOffTest extends Neo4jContainerTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private Neo4jClient neo4j;

    private String p;

    @BeforeEach
    void seed() {
        p = M3AuthFixtures.newPrefix();
        M3AuthFixtures.seed(neo4j, p, "author-sub");
    }

    @AfterEach
    void clean() {
        M3AuthFixtures.clean(neo4j, p);
    }

    @Test
    void packetsListsEveryListedPacket_butNeverEphemeral_andMineIsEmpty() throws Exception {
        graphql("{ packets(filter: {nameContains: \"" + p + "\"}) { total items { id } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.packets.total").value(3))
                .andExpect(jsonPath("$.data.packets.items[*].id").value(contains(p + "draft", p + "ownerless", p + "pub")));
        graphql("{ packets(filter: {mine: true, nameContains: \"" + p + "\"}) { total } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.packets.total").value(0));
    }

    @Test
    void exportAndCloneWorkOnAnyPacket() throws Exception {
        graphql("{ exportPacket(id: \"" + p + "draft\") }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.exportPacket", containsString("Answer-draft")));
        graphql("mutation { clonePacket(id: \"" + p + "draft\", name: \"" + p + "clone\") { name visibility owner { id } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.clonePacket.name").value(p + "clone"))
                .andExpect(jsonPath("$.data.clonePacket.visibility").value("DRAFT"))
                .andExpect(jsonPath("$.data.clonePacket.owner").value(nullValue()));
    }

    @Test
    void importCommitsAnOwnerlessDraft() throws Exception {
        graphql("mutation($text: String!) { importPacket(input: {text: $text, name: \"" + p + "imported\", dryRun: false}) "
                        + "{ committed packet { name visibility owner { id } } } }",
                Map.of("text", M3AuthFixtures.IMPORT_TEXT))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.importPacket.committed").value(true))
                .andExpect(jsonPath("$.data.importPacket.packet.visibility").value("DRAFT"))
                .andExpect(jsonPath("$.data.importPacket.packet.owner").value(nullValue()));
    }

    @Test
    void taxonomyRenameAndMergeNeedNoToken() throws Exception {
        graphql("mutation { renameCategory(id: \"" + p + "cat\", name: \"" + p + "Renamed\") { name } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.renameCategory.name").value(p + "Renamed"));
        graphql("mutation { mergeDifficulties(sourceId: \"" + p + "diff2\", targetId: \"" + p + "diff\") { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void publishAndSubcategoryClearWork_andExpectedVersionIsStillChecked() throws Exception {
        graphql("mutation { setPacketVisibility(id: \"" + p + "draft\", visibility: PUBLISHED, expectedVersion: 0) { version } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.setPacketVisibility.version").value(1));
        graphql("mutation { setTossupSubcategory(tossupId: \"" + p + "draft-t\", subcategoryId: null, expectedVersion: 0) { id } }")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("CONFLICT"))
                .andExpect(jsonPath("$.errors[0].extensions.currentVersion").value(1));
        graphql("mutation { setTossupSubcategory(tossupId: \"" + p + "draft-t\", subcategoryId: null, expectedVersion: 1) "
                + "{ subcategory { id } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.setTossupSubcategory.subcategory").value(nullValue()));
    }

    private ResultActions graphql(String query) throws Exception {
        return GraphQlAuthTestSupport.graphql(mvc, query);
    }

    private ResultActions graphql(String query, Map<String, Object> variables) throws Exception {
        return GraphQlAuthTestSupport.graphql(mvc, query, variables);
    }
}

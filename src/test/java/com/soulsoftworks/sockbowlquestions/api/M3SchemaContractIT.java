package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.execution.SchemaReport;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 Q1 schema contract (plans/m3-packets.md 3.1.11) against the booted application
 * (auth off, real Neo4j):
 * <ul>
 *   <li>every M3 query, mutation, type and in-place argument change is in the executable schema;</li>
 *   <li>the schema inspection report lists no unmapped field outside the documented
 *       {@code TODO(Qn)} set (later WPs map them; the set only shrinks);</li>
 *   <li>{@code expectedVersion} and a null {@code subcategoryId} pass GraphQL validation and
 *       reach the resolvers;</li>
 *   <li>the taxonomy create mutations still work after moving to {@link TaxonomyController}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class M3SchemaContractIT extends Neo4jContainerTestBase {

    private static final String TAXONOMY_PREFIX = "M3SchemaContractIT-";

    /**
     * Fields the M3 block declares before their work package maps them. Anything unmapped
     * outside this set is a regression (for example a controller method that lost its mapping).
     */
    private static final Set<String> ALLOWED_UNMAPPED = Set.of(
            "Query.packets",            // TODO(Q5)
            "Mutation.renameCategory",  // TODO(Q4)
            "Mutation.renameSubcategory",
            "Mutation.renameDifficulty",
            "Mutation.mergeCategories",
            "Mutation.mergeSubcategories",
            "Mutation.mergeDifficulties",
            "Packet.version",           // TODO(Q2)
            "Packet.validation");       // TODO(Q2)

    /** Every packet/content mutation that must take {@code expectedVersion: Int} (last argument). */
    private static final List<String> VERSIONED_MUTATIONS = List.of(
            "renamePacket", "setPacketDifficulty", "deletePacket", "setPacketVisibility",
            "addTossupToPacket", "updateTossup", "removeTossupFromPacket", "reorderTossup",
            "addBonusToPacket", "updateBonus", "removeBonusFromPacket", "reorderBonus",
            "addBonusPart", "updateBonusPart", "removeBonusPart", "reorderBonusPart",
            "setTossupSubcategory", "setBonusSubcategory", "generateAndAddTossup");

    @TestConfiguration
    static class CaptureSchemaReport {
        static final AtomicReference<SchemaReport> REPORT = new AtomicReference<>();

        @Bean
        GraphQlSourceBuilderCustomizer captureSchemaReport() {
            return builder -> builder.inspectSchemaMappings(REPORT::set);
        }
    }

    @Autowired private GraphQlSource graphQlSource;
    @Autowired private MockMvc mvc;
    @Autowired private Neo4jClient neo4j;

    @AfterEach
    void cleanTaxonomy() {
        neo4j.query("""
                MATCH (n) WHERE (n:Category OR n:Subcategory OR n:Difficulty) AND n.name STARTS WITH $prefix
                DETACH DELETE n
                """).bind(TAXONOMY_PREFIX).to("prefix").run();
    }

    /* ------------------------------ schema shape ------------------------------ */

    @Test
    void schemaHasEveryM3Operation() {
        GraphQLSchema schema = graphQlSource.schema();
        assertThat(fieldNames(schema.getQueryType())).contains("packets", "exportPacket", "getAllPackets");
        assertThat(fieldNames(schema.getMutationType())).contains(
                "importPacket", "clonePacket",
                "renameCategory", "renameSubcategory", "renameDifficulty",
                "mergeCategories", "mergeSubcategories", "mergeDifficulties",
                "createDifficulty", "createCategory", "createSubcategory");
        assertThat(fieldNames(schema.getObjectType("Packet"))).contains("version", "validation", "visibility");
        for (String type : List.of("PacketPage", "PacketSummary", "PacketValidation", "ValidationIssue",
                "ImportPacketResult", "ParsedPacket", "ParsedTossup", "ParsedBonus", "ParsedBonusPart",
                "ImportIssue", "PacketFilter", "ImportPacketInput", "IssueSeverity", "PacketExportFormat")) {
            assertThat(schema.getType(type)).as(type).isNotNull();
        }
    }

    @Test
    void packetsQueryArgumentsHaveTheirDefaults() {
        GraphQLFieldDefinition packets = graphQlSource.schema().getQueryType().getFieldDefinition("packets");
        assertThat(packets.getArgument("page").getArgumentDefaultValue().getValue()).isNotNull();
        assertThat(packets.getArgument("size").getArgumentDefaultValue().getValue()).isNotNull();
        assertThat(packets.getType()).isInstanceOf(GraphQLNonNull.class);
    }

    @Test
    void getAllPacketsIsDeprecated() {
        GraphQLFieldDefinition getAll = graphQlSource.schema().getQueryType().getFieldDefinition("getAllPackets");
        assertThat(getAll.isDeprecated()).isTrue();
        assertThat(getAll.getDeprecationReason()).isEqualTo("use packets");
    }

    @Test
    void everyContentMutationTakesAnOptionalExpectedVersionLast() {
        GraphQLObjectType mutation = graphQlSource.schema().getMutationType();
        for (String name : VERSIONED_MUTATIONS) {
            GraphQLFieldDefinition field = mutation.getFieldDefinition(name);
            assertThat(field).as(name).isNotNull();
            List<GraphQLArgument> args = field.getArguments();
            GraphQLArgument last = args.get(args.size() - 1);
            assertThat(last.getName()).as(name).isEqualTo("expectedVersion");
            assertThat(last.getType()).as(name + " expectedVersion is nullable")
                    .isNotInstanceOf(GraphQLNonNull.class);
        }
        // Creation and taxonomy mutations don't target an existing packet.
        assertThat(mutation.getFieldDefinition("createPacket").getArgument("expectedVersion")).isNull();
        assertThat(mutation.getFieldDefinition("createCategory").getArgument("expectedVersion")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"setTossupSubcategory", "setBonusSubcategory"})
    void setSubcategoryAcceptsNullToClear(String mutationName) {
        GraphQLArgument arg = graphQlSource.schema().getMutationType()
                .getFieldDefinition(mutationName).getArgument("subcategoryId");
        assertThat(arg.getType()).isNotInstanceOf(GraphQLNonNull.class);
    }

    @Test
    void onlyDocumentedTodoFieldsAreUnmapped() {
        SchemaReport report = CaptureSchemaReport.REPORT.get();
        assertThat(report).as("schema inspection ran (spring.graphql.schema.inspection.enabled)").isNotNull();
        Set<String> unmapped = report.unmappedFields().stream()
                .map(M3SchemaContractIT::coordinates)
                .collect(Collectors.toSet());
        assertThat(unmapped).isSubsetOf(ALLOWED_UNMAPPED);
    }

    /* ---------------------------- through /graphql ---------------------------- */

    @Test
    void expectedVersionIsAcceptedAndReachesTheResolver() throws Exception {
        // Auth off: no method security, so the resolver runs and reports the missing packet.
        graphql("mutation { renamePacket(id: \"m3-contract-missing\", name: \"x\", expectedVersion: 3) { id } }")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("NOT_FOUND"));
        graphql("mutation { deletePacket(id: \"m3-contract-missing\", expectedVersion: 0) }")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("NOT_FOUND"));
    }

    @Test
    void nullSubcategoryPassesValidation() throws Exception {
        graphql("mutation { setTossupSubcategory(tossupId: \"m3-contract-missing\", subcategoryId: null) { id } }")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("NOT_FOUND"));
    }

    @Test
    void taxonomyCreateStillWorksThroughTaxonomyController() throws Exception {
        String category = TAXONOMY_PREFIX + "Science";
        MvcResult created = graphql("mutation { createCategory(name: \"" + category + "\") { id name } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createCategory.name").value(category))
                .andReturn();
        String categoryId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.createCategory.id");

        graphql("mutation { createSubcategory(name: \"" + TAXONOMY_PREFIX + "Bio\", categoryId: \"" + categoryId
                + "\") { name category { id } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createSubcategory.category.id").value(categoryId));
        graphql("mutation { createDifficulty(name: \"" + TAXONOMY_PREFIX + "Hard\") { name } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createDifficulty.name").value(TAXONOMY_PREFIX + "Hard"));
    }

    /* --------------------------------- helpers -------------------------------- */

    private static Set<String> fieldNames(GraphQLObjectType type) {
        return type.getFieldDefinitions().stream().map(GraphQLFieldDefinition::getName).collect(Collectors.toSet());
    }

    private static String coordinates(FieldCoordinates c) {
        return c.getTypeName() + "." + c.getFieldName();
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

package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.ratelimit.graphql.GraphQlLimitsProperties;
import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.introspection.IntrospectionQuery;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.language.VariableDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import graphql.schema.idl.TypeUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-Q2 acceptance: the static depth and complexity caps, with the <b>shipped</b>
 * {@code sockbowl.ratelimit.graphql.*} values from {@code application.yml} and
 * the real {@code schema.graphqls}, through the same instrumentation beans
 * {@link GraphQlLimitsConfig} registers.
 *
 * <ul>
 *   <li>A depth-16 document is rejected; depth 15 and the standard introspection
 *       query are accepted.</li>
 *   <li>Every GraphQL document ng and game send (copied under
 *       {@code src/test/resources/graphql-documents/}) is accepted and keeps at
 *       least 2x headroom under the complexity cap (a regression guard; INT1 adds
 *       M3's documents here).</li>
 *   <li>An {@code getAllPackets}-style document over the cap is rejected.</li>
 * </ul>
 */
class GraphQlDepthComplexityTest {

    private static GraphQLSchema schema;
    private static GraphQlLimitsProperties properties;
    private static GraphQL limited;

    @BeforeAll
    static void loadSchemaAndShippedCaps() throws IOException {
        TypeDefinitionRegistry registry = new SchemaParser().parse(
                new ClassPathResource("graphql/schema.graphqls").getContentAsString(StandardCharsets.UTF_8));
        schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);

        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        yaml.forEach(environment.getPropertySources()::addLast);
        properties = Binder.get(environment)
                .bind("sockbowl.ratelimit.graphql", GraphQlLimitsProperties.class)
                .orElseThrow(() -> new IllegalStateException("sockbowl.ratelimit.graphql is not shipped"));

        GraphQlLimitsConfig config = new GraphQlLimitsConfig();
        limited = GraphQL.newGraphQL(schema)
                .instrumentation(new ChainedInstrumentation(
                        config.maxQueryDepthInstrumentation(properties),
                        config.maxQueryComplexityInstrumentation(properties)))
                .build();
    }

    /* ------------------------------ shipped caps ------------------------------ */

    @Test
    void theShippedCapsAreTheCalibratedOnes() {
        assertThat(properties.getMaxDepth()).isEqualTo(15);
        assertThat(properties.getMaxComplexity()).isEqualTo(82);
        assertThat(properties.getDefaultQueryPolicy()).isEqualTo("graphql-read");
        assertThat(properties.getDefaultMutationPolicy()).isEqualTo("graphql-write");
        assertThat(properties.getFields()).containsEntry("generateAndAddTossup", "graphql-write");
    }

    /* ---------------------------------- depth --------------------------------- */

    @Test
    void aDepth16DocumentIsRejected() {
        String query = introspectionOfDepth(16);
        assertThat(measure(query, null).depth()).isEqualTo(16);

        ExecutionResult result = limited.execute(query);

        assertAborted(result, "maximum query depth exceeded 16 > 15");
    }

    @Test
    void aDepth15DocumentIsAccepted() {
        String query = introspectionOfDepth(15);
        assertThat(measure(query, null).depth()).isEqualTo(15);

        assertNotAborted(limited.execute(query));
    }

    @Test
    void theStandardIntrospectionQueryIsAccepted() {
        Measure measure = measure(IntrospectionQuery.INTROSPECTION_QUERY, null);
        assertThat(measure.depth()).isLessThanOrEqualTo(properties.getMaxDepth());
        assertThat(measure.complexity()).as("introspection fields are free").isZero();

        assertNotAborted(limited.execute(IntrospectionQuery.INTROSPECTION_QUERY));
    }

    /* ------------------------------- complexity ------------------------------- */

    @TestFactory
    Stream<DynamicTest> everyClientDocumentIsAcceptedWithTwiceTheHeadroom() throws IOException {
        List<ClientDocument> documents = clientDocuments();
        assertThat(documents).as("ng + game documents").hasSizeGreaterThanOrEqualTo(29);
        return documents.stream().map(doc -> DynamicTest.dynamicTest(doc.name(), () -> {
            Map<String, Object> variables = variablesFor(doc.text());
            Measure measure = measure(doc.text(), variables);
            assertThat(measure.depth()).as("depth of %s", doc.name())
                    .isLessThanOrEqualTo(properties.getMaxDepth());
            assertThat(measure.complexity() * 2).as("2x complexity of %s", doc.name())
                    .isLessThanOrEqualTo(properties.getMaxComplexity());

            ExecutionResult result = limited.execute(ExecutionInput.newExecutionInput(doc.text())
                    .variables(variables).build());
            assertNotAborted(result);
        }));
    }

    @Test
    void theCapIsTwiceTheLargestClientDocument() throws IOException {
        int max = 0;
        String largest = null;
        for (ClientDocument doc : clientDocuments()) {
            int complexity = measure(doc.text(), variablesFor(doc.text())).complexity();
            if (complexity > max) {
                max = complexity;
                largest = doc.name();
            }
        }
        assertThat(largest).isEqualTo("game/PacketClient_QUERY");
        assertThat(max * 2).as("the cap is 2x the largest document (%s)", largest)
                .isEqualTo(properties.getMaxComplexity());
    }

    @Test
    void anAllPacketsStyleQueryOverTheCapIsRejected() throws IOException {
        String allPackets = clientDocuments().stream()
                .filter(d -> d.name().equals("ng/getAllPackets")).findFirst().orElseThrow().text();
        String selection = allPackets.substring(allPackets.indexOf("getAllPackets {") + "getAllPackets".length(),
                allPackets.lastIndexOf('}'));
        String threeTimes = "query { a: getAllPackets" + selection + " b: getAllPackets" + selection
                + " c: getAllPackets" + selection + " }";
        int complexity = measure(threeTimes, null).complexity();
        assertThat(complexity).isGreaterThan(properties.getMaxComplexity());

        assertAborted(limited.execute(threeTimes),
                "maximum query complexity exceeded " + complexity + " > " + properties.getMaxComplexity());

        String twice = "query { a: getAllPackets" + selection + " b: getAllPackets" + selection + " }";
        assertThat(measure(twice, null).complexity()).isLessThanOrEqualTo(properties.getMaxComplexity());
        assertNotAborted(limited.execute(twice));
    }

    @Test
    void aWideAliasedBatchOverTheCapIsRejected() {
        StringBuilder query = new StringBuilder("query {");
        for (int i = 0; i < properties.getMaxComplexity(); i++) {
            query.append(" d").append(i).append(": getAllDifficulties { id }");
        }
        query.append(" }");

        assertAborted(limited.execute(query.toString()), "maximum query complexity exceeded");
    }

    /* --------------------------------- helpers -------------------------------- */

    record ClientDocument(String name, String text) {
    }

    record Measure(int depth, int complexity) {
    }

    static List<ClientDocument> clientDocuments() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:graphql-documents/*/*.graphql");
        List<ClientDocument> documents = new ArrayList<>();
        for (Resource resource : resources) {
            String path = resource.getURL().getPath();
            String dir = path.substring(0, path.lastIndexOf('/'));
            String name = dir.substring(dir.lastIndexOf('/') + 1) + "/"
                    + resource.getFilename().replace(".graphql", "");
            documents.add(new ClientDocument(name, resource.getContentAsString(StandardCharsets.UTF_8)));
        }
        documents.sort(Comparator.comparing(ClientDocument::name));
        return documents;
    }

    /** Depth and complexity as graphql-java's own instrumentations count them. */
    static Measure measure(String query, Map<String, Object> variables) {
        AtomicInteger depth = new AtomicInteger(-1);
        AtomicInteger complexity = new AtomicInteger(-1);
        GraphQL measuring = GraphQL.newGraphQL(schema)
                .instrumentation(new ChainedInstrumentation(
                        new MaxQueryDepthInstrumentation(-1, info -> {
                            depth.set(info.getDepth());
                            return false;
                        }),
                        new MaxQueryComplexityInstrumentation(-1, GraphQlLimitsConfig.COMPLEXITY_CALCULATOR, info -> {
                            complexity.set(info.getComplexity());
                            return false;
                        })))
                .build();
        ExecutionResult result = measuring.execute(ExecutionInput.newExecutionInput(query)
                .variables(variables == null ? Map.of() : variables).build());
        assertThat(result.getErrors()).as("%s must be a valid document", query)
                .noneMatch(e -> e.getErrorType() == ErrorType.ValidationError
                        || e.getErrorType() == ErrorType.InvalidSyntax);
        assertThat(depth.get()).as("depth measured").isNotNegative();
        return new Measure(depth.get(), Math.max(0, complexity.get()));
    }

    /**
     * {@code { __type(name:"Packet") { fields { type { ofType { ... name } } } } }}
     * nested so that graphql-java counts exactly {@code depth}.
     */
    static String introspectionOfDepth(int depth) {
        // __type (1) > fields (2) > type (3) > ofType x (depth - 4) > name (depth)
        int ofTypes = depth - 4;
        return "{ __type(name: \"Packet\") { fields { type { " + "ofType { ".repeat(ofTypes) + "name"
                + " }".repeat(ofTypes) + " } } } }";
    }

    static void assertAborted(ExecutionResult result, String messageStart) {
        assertThat(result.getErrors()).as("errors").isNotEmpty();
        GraphQLError error = result.getErrors().getFirst();
        assertThat(error.getErrorType()).isEqualTo(ErrorType.ExecutionAborted);
        assertThat(error.getMessage()).startsWith(messageStart);
        assertThat((Object) result.getData()).isNull();
    }

    static void assertNotAborted(ExecutionResult result) {
        assertThat(result.getErrors()).as("errors of an accepted document")
                .noneMatch(e -> e.getErrorType() == ErrorType.ExecutionAborted
                        || e.getErrorType() == ErrorType.ValidationError
                        || e.getErrorType() == ErrorType.InvalidSyntax);
    }

    /** Minimal valid values for the document's variables (required ones only). */
    static Map<String, Object> variablesFor(String query) {
        Document document = Parser.parse(query);
        Map<String, Object> variables = new LinkedHashMap<>();
        for (OperationDefinition operation : document.getDefinitionsOfType(OperationDefinition.class)) {
            for (VariableDefinition definition : operation.getVariableDefinitions()) {
                if (!TypeUtil.isNonNull(definition.getType())) {
                    continue;
                }
                GraphQLType type = schema.getType(TypeUtil.unwrapAll(definition.getType()).getName());
                Object value = sampleOf((GraphQLInputType) type);
                variables.put(definition.getName(), TypeUtil.isList(TypeUtil.unwrapOne(definition.getType()))
                        ? List.of(value) : value);
            }
        }
        return variables;
    }

    private static Object sampleOf(GraphQLInputType type) {
        GraphQLType unwrapped = GraphQLTypeUtil.unwrapAll(type);
        if (unwrapped instanceof GraphQLEnumType enumType) {
            return enumType.getValues().getFirst().getName();
        }
        if (unwrapped instanceof GraphQLInputObjectType object) {
            Map<String, Object> value = new LinkedHashMap<>();
            for (GraphQLInputObjectField field : object.getFieldDefinitions()) {
                if (field.getType() instanceof GraphQLNonNull) {
                    Object sample = sampleOf(field.getType());
                    value.put(field.getName(), GraphQLTypeUtil.unwrapNonNull(field.getType()) instanceof GraphQLList
                            ? List.of(sample) : sample);
                }
            }
            return value;
        }
        String scalar = ((GraphQLScalarType) unwrapped).getName();
        return switch (scalar) {
            case "Int" -> 1;
            case "Float" -> 1.0;
            case "Boolean" -> true;
            default -> "x";
        };
    }
}

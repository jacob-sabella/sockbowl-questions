package com.soulsoftworks.sockbowlquestions.tooling;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Emits a JSON Schema of the published question models — the machine-readable
 * contract downstream clients codegen from. sockbowl-questions remains the
 * single source of truth; the ng client runs quicktype against this schema
 * instead of hand-maintaining TypeScript types.
 *
 * <p>Generated from {@link Packet} (the aggregate root), which reaches every
 * model via its relationships. Field-based introspection with plain definition
 * keys keeps the {@code $defs} named after the classes (Packet, Tossup, Bonus,
 * …) so the generated TypeScript type names match.
 *
 * <p>Run via {@code ./gradlew generateModelSchema}.
 */
public final class ModelSchemaGenerator {

    private ModelSchemaGenerator() {}

    public static void main(String[] args) throws Exception {
        SchemaGeneratorConfigBuilder builder =
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
                        .with(Option.PLAIN_DEFINITION_KEYS);
        SchemaGeneratorConfig config = builder.build();
        SchemaGenerator generator = new SchemaGenerator(config);

        ObjectNode schema = generator.generateSchema(Packet.class);
        schema.put("title", "Packet");

        Path out = Path.of(args.length > 0 ? args[0] : "build/questions-models.schema.json");
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, schema.toPrettyString());
        System.out.println("Wrote model JSON Schema to " + out.toAbsolutePath());
    }
}

package com.soulsoftworks.sockbowlquestions.migration;

import com.soulsoftworks.sockbowlquestions.service.TaxonomyService;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Starter difficulty descriptions against a real Neo4j: seeded only where none was
 * ever set, never over an edit or an intentional clear, and editable through
 * {@link TaxonomyService#setDifficultyDescription}.
 */
@SpringBootTest
class DifficultyDescriptionSeederTest extends Neo4jContainerTestBase {

    private static final String KEY = "college";

    @Autowired private DifficultyDescriptionSeeder seeder;
    @Autowired private TaxonomyService taxonomyService;
    @Autowired private Neo4jClient neo4j;

    private String id;
    private boolean created;

    @BeforeEach
    void difficulty() {
        created = neo4j.query("MATCH (d:Difficulty {nameKey: $key}) RETURN count(d)").bind(KEY).to("key")
                .fetchAs(Long.class).one().orElse(0L) == 0;
        id = neo4j.query("""
                MERGE (d:Difficulty {nameKey: $key}) ON CREATE SET d.id = randomUUID(), d.name = 'College'
                REMOVE d.description RETURN d.id
                """).bind(KEY).to("key").fetchAs(String.class).one().orElseThrow();
    }

    @AfterEach
    void clean() {
        if (created) {
            neo4j.query("MATCH (d:Difficulty {id: $id}) DETACH DELETE d").bind(id).to("id").run();
        }
    }

    private String description() {
        return neo4j.query("MATCH (d:Difficulty {id: $id}) RETURN d.description").bind(id).to("id")
                .fetchAs(String.class).one().orElse(null);
    }

    @Test
    void seedsOnlyDifficultiesThatNeverHadADescription() {
        assertThat(seeder.seed()).isGreaterThanOrEqualTo(1);
        assertThat(description()).isEqualTo(DifficultyDescriptionSeeder.STARTER_DESCRIPTIONS.get(KEY));

        assertThat(taxonomyService.setDifficultyDescription(id, "  Our league's college level.  ").getDescription())
                .isEqualTo("Our league's college level.");
        seeder.seed();
        assertThat(description()).isEqualTo("Our league's college level.");

        taxonomyService.setDifficultyDescription(id, null);
        seeder.seed();
        assertThat(description()).isEmpty();
    }

    @Test
    void descriptionsHaveALengthCap() {
        assertThatThrownBy(() -> taxonomyService.setDifficultyDescription(id, "x".repeat(TaxonomyService.DESCRIPTION_MAX + 1)))
                .hasMessageContaining("exceeds");
    }
}

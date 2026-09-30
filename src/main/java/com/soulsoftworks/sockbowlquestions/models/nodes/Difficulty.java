package com.soulsoftworks.sockbowlquestions.models.nodes;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

@Node
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Difficulty {
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;
    private String name;

    /**
     * {@code toLower(trim(name))}, maintained by {@code TaxonomyService} (M3 Q4, D4).
     * Not exposed in GraphQL; used for case-insensitive idempotent create, rename
     * collision detection and the {@code difficulty_namekey} uniqueness constraint.
     */
    private String nameKey;

    /**
     * What this level means (who the players are, how hard the answers and clues
     * should be), edited in the taxonomy admin. AI generation puts it in every
     * prompt for a packet at this difficulty. An empty string means "cleared on
     * purpose", so {@code DifficultyDescriptionSeeder} leaves it alone.
     */
    private String description;
}
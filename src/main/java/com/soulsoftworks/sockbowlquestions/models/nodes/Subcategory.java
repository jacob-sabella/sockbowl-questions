package com.soulsoftworks.sockbowlquestions.models.nodes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.util.List;

@Node
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subcategory {
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;
    private String name;

    /**
     * {@code toLower(trim(name))}, maintained by {@code TaxonomyService} (M3 Q4, D4).
     * Not exposed in GraphQL; scoped by category (two categories may each have a
     * subcategory with the same {@code nameKey}), so there is no global uniqueness
     * constraint on this field.
     */
    private String nameKey;

    @Relationship(type = "SUBCATEGORY_OF", direction = Relationship.Direction.OUTGOING)
    private Category category;
}
   

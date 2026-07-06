package com.soulsoftworks.sockbowlquestions.models.nodes;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;


@Node
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tossup {
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;
    private String question;
    private String answer;

    @Relationship(type = "SUBCATEGORY_IS", direction = Relationship.Direction.OUTGOING)
    private Subcategory subcategory;
}

package com.soulsoftworks.sockbowlquestions.models.nodes;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import lombok.Data;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.util.List;

@Node
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class)
@Data
public class Bonus {
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;
    private String preamble;

    @Relationship(type = "SUBCATEGORY_IS", direction = Relationship.Direction.INCOMING)
    private Subcategory subcategory;

    @Relationship(type = "HAS_PART", direction = Relationship.Direction.OUTGOING)
    private List<HasBonusPart> bonusParts;
}

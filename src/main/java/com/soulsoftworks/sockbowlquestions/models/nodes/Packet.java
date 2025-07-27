package com.soulsoftworks.sockbowlquestions.models.nodes;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import lombok.Builder;
import lombok.Data;
import lombok.Singular;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.util.List;

@Node
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@Data
@Builder
public class Packet {
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;
    private String name;

    @Relationship(type = "DIFFICULTY_LEVEL", direction = Relationship.Direction.OUTGOING)
    private Difficulty difficulty;

    @Relationship(type = "CONTAINS_TOSSUP", direction = Relationship.Direction.OUTGOING)
    @Singular
    private List<ContainsTossup> tossups;

    @Relationship(type = "CONTAINS_BONUS", direction = Relationship.Direction.OUTGOING)
    private List<ContainsBonus> bonuses;


}
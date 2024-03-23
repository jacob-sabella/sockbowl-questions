package com.soulsoftworks.sockbowlquestions.models.nodes;


import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.soulsoftworks.sockbowlquestions.models.relationships.UsesPacketAtRound;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.util.List;

@Node
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Event {
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;
    private String location;
    private String name;
    private Integer year;
    private Boolean imported;

    @Relationship(type = "USES_PACKET_AT_ROUND", direction = Relationship.Direction.OUTGOING)
    private List<UsesPacketAtRound> packets;
}


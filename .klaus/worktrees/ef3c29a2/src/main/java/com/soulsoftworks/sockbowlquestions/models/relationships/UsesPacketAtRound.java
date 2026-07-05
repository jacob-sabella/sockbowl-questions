package com.soulsoftworks.sockbowlquestions.models.relationships;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

@RelationshipProperties
public class UsesPacketAtRound {
    @RelationshipId
    private Long id;
    private final Integer round;

    @TargetNode private final Packet packet;

    public UsesPacketAtRound(Integer round, Packet packet) {
        this.round = round;
        this.packet = packet;
    }

    // Getters and setters
}

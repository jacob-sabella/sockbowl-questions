package com.soulsoftworks.sockbowlquestions.models.relationships;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

@RelationshipProperties
public class ContainsBonus {
    @RelationshipId
    private Long id;
    private Integer order;

    @TargetNode
    private Bonus bonus;
}

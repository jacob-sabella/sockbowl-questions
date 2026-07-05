package com.soulsoftworks.sockbowlquestions.models.relationships;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

@RelationshipProperties
public class ContainsBonus {
    @RelationshipId
    private Long id;
    private final Integer order;

    @TargetNode
    private final Bonus bonus;

    // Constructor
    public ContainsBonus(Integer order, Bonus bonus) {
        this.order = order;
        this.bonus = bonus;
    }

    public Integer getOrder() {
        return order;
    }

    public Bonus getBonus() {
        return bonus;
    }
}

package com.soulsoftworks.sockbowlquestions.models.relationships;

import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

@RelationshipProperties
public class HasBonusPart {
    @RelationshipId
    private Long id;
    private final Integer order; // This stores the order of the BonusPart in the Bonus

    @TargetNode private final BonusPart bonusPart;

    // Constructor
    public HasBonusPart(Integer order, BonusPart bonusPart) {
        this.order = order;
        this.bonusPart = bonusPart;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Integer getOrder() {
        return order;
    }

    public BonusPart getBonusPart() {
        return bonusPart;
    }

}
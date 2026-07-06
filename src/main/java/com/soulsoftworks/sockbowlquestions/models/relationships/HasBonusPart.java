package com.soulsoftworks.sockbowlquestions.models.relationships;

import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

@RelationshipProperties
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HasBonusPart {
    @RelationshipId
    private Long id;
    /** The order of the BonusPart within the Bonus. */
    private Integer order;
    @TargetNode
    private BonusPart bonusPart;

    /** Convenience constructor (id is assigned by Neo4j). */
    public HasBonusPart(Integer order, BonusPart bonusPart) {
        this.order = order;
        this.bonusPart = bonusPart;
    }
}

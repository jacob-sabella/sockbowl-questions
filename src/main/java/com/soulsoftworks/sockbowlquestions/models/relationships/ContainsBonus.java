package com.soulsoftworks.sockbowlquestions.models.relationships;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
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
public class ContainsBonus {
    @RelationshipId
    private Long id;
    private Integer order;
    @TargetNode
    private Bonus bonus;

    /** Convenience constructor (id is assigned by Neo4j). */
    public ContainsBonus(Integer order, Bonus bonus) {
        this.order = order;
        this.bonus = bonus;
    }
}

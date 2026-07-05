package com.soulsoftworks.sockbowlquestions.models.relationships;

import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

@RelationshipProperties
@Data
@Builder
public class ContainsTossup {
    @RelationshipId
    private Long id;

    private Integer order;

    @TargetNode
    private Tossup tossup;

}

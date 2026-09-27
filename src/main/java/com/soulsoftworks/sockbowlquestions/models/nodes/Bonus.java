package com.soulsoftworks.sockbowlquestions.models.nodes;

import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder;
import lombok.AllArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.time.Instant;
import java.util.List;

@Node
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Bonus {
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;
    private String preamble;
    /** Source question id from the origin (e.g. qbreader _id); null for authored content. */
    private String remoteId;

    @Relationship(type = "SUBCATEGORY_IS", direction = Relationship.Direction.INCOMING)
    private Subcategory subcategory;

    @Relationship(type = "HAS_PART", direction = Relationship.Direction.OUTGOING)
    private List<HasBonusPart> bonusParts;

    /** How this bonus was made (D13, M4-PV-01). Set explicitly by application code. */
    private ContentSource source;

    /** The model that generated this bonus, when {@link #source} is {@code AI_GENERATED}; null otherwise. */
    private String aiModel;

    /** See {@link Packet#getCreatedBy()}. */
    @CreatedBy
    private String createdBy;

    /** See {@link Packet#getCreatedAt()}. */
    @CreatedDate
    private Instant createdAt;

    /** See {@link Packet#getLastModifiedBy()}. */
    @LastModifiedBy
    private String lastModifiedBy;

    /** See {@link Packet#getLastModifiedAt()}. */
    @LastModifiedDate
    private Instant lastModifiedAt;
}

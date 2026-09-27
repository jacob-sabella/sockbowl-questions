package com.soulsoftworks.sockbowlquestions.models.nodes;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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


@Node
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Tossup {
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;
    private String question;
    private String answer;
    /** Source question id from the origin (e.g. qbreader _id); null for authored content. */
    private String remoteId;

    @Relationship(type = "SUBCATEGORY_IS", direction = Relationship.Direction.OUTGOING)
    private Subcategory subcategory;

    /** How this tossup was made (D13, M4-PV-01). Set explicitly by application code. */
    private ContentSource source;

    /** The model that generated this tossup, when {@link #source} is {@code AI_GENERATED}; null otherwise. */
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

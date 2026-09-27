package com.soulsoftworks.sockbowlquestions.models.nodes;

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
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.time.Instant;

@Node
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class BonusPart {
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;
    private String question;
    private String answer;

    /** How this bonus part was made (D13, M4-PV-01). Set explicitly by application code. */
    private ContentSource source;

    /** The model that generated this bonus part, when {@link #source} is {@code AI_GENERATED}; null otherwise. */
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

package com.soulsoftworks.sockbowlquestions.models.nodes;

import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Singular;
import org.springframework.data.annotation.Transient;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.util.List;

@Node
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Packet {
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;
    private String name;

    /** Keycloak {@code sub} of the packet's creator; null = anonymous/legacy packet. */
    private String ownerId;

    /** {@code preferred_username} claim captured at creation time; null if anonymous. */
    private String ownerDisplayName;

    /**
     * Who may read this packet (D2). New packets default to {@link PacketVisibility#DRAFT};
     * null only on legacy nodes, which count as {@link PacketVisibility#PUBLISHED}
     * (see {@link PacketVisibility#effective(PacketVisibility)}).
     */
    private PacketVisibility visibility;

    /**
     * True on an answer-free projection served to a caller who may not read answers;
     * null (read as false) otherwise. Never persisted: it describes a response, not the
     * stored packet. A wrapper type on purpose, so JSON consumers that bind through the
     * all-args constructor (sockbowl-game's Jackson 3 decoding) accept payloads that
     * don't carry the field.
     */
    @Transient
    private Boolean answersRedacted;

    @Relationship(type = "DIFFICULTY_LEVEL", direction = Relationship.Direction.OUTGOING)
    private Difficulty difficulty;

    @Relationship(type = "CONTAINS_TOSSUP", direction = Relationship.Direction.OUTGOING)
    @Singular
    private List<ContainsTossup> tossups;

    @Relationship(type = "CONTAINS_BONUS", direction = Relationship.Direction.OUTGOING)
    @Singular
    private List<ContainsBonus> bonuses;


}
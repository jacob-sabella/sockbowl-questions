package com.soulsoftworks.sockbowlquestions.aikey;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.time.Instant;

/**
 * A user's saved AI provider key, one per Keycloak user. The key itself is only
 * ever stored as AES-GCM ciphertext ({@link AiKeyCipher}); {@link #last4} and
 * {@link #model} are what the profile page shows back.
 *
 * <p>Deliberately outside {@code models.*}: it is not part of the published
 * question models jar or the GraphQL schema.
 */
@Node("UserAiKey")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAiKey {
    /** The Keycloak subject ({@code sub}) that owns the key. */
    @Id
    private String keycloakId;
    private String provider;
    @ToString.Exclude
    private String ciphertext;
    private String model;
    private String last4;
    private Instant createdAt;
    private Instant updatedAt;
}

package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared set-up of the WP-Q2 field-level GraphQL limiter ITs: on top of
 * {@link QuestionsRequestGuardITSupport} (real Neo4j and Redis, shipped
 * policies, limiter on, {@code MutableClock}), seeds packets and parses GraphQL
 * responses sent over {@code POST /graphql}.
 */
abstract class GraphQlRateLimitITSupport extends QuestionsRequestGuardITSupport {

    static final String NAME_PREFIX = "q2-rl-";

    @Autowired
    PacketRepository packetRepository;

    @Autowired
    Neo4jClient neo4j;

    @Autowired
    RateLimitProperties rateLimitProperties;

    @AfterEach
    void deleteSeededPackets() {
        neo4j.query("""
                MATCH (p:Packet) WHERE p.name STARTS WITH $prefix
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t)
                DETACH DELETE p, t
                """).bind(NAME_PREFIX).to("prefix").run();
    }

    /** A packet with no content, owned by {@code ownerId} ({@code null} = ownerless). */
    String seedPacket(String ownerId) {
        return packetRepository.batchCreatePacket(NAME_PREFIX + UUID.randomUUID(), "Easy", List.of(), List.of(),
                ownerId, ownerId == null ? null : "owner-" + ownerId, PacketVisibility.DRAFT.name(), null,
                ownerId == null ? "anonymous" : ownerId, Instant.now().toString(), ContentSource.AUTHORED.name());
    }

    String packetName(String id) {
        return packetRepository.findById(id).orElseThrow().getName();
    }

    /** The tier-scaled capacity of a shipped policy. */
    long capacityOf(String policy, Tier tier) {
        PolicySpec spec = rateLimitProperties.policy(policy);
        return Math.max(1, Math.round(spec.getCapacity() * rateLimitProperties.multiplierFor(spec, tier)));
    }

    static String renameMutation(String packetId, String name) {
        return "mutation { renamePacket(id: \"" + packetId + "\", name: \"" + name + "\") { id name } }";
    }

    /** {@code count} aliased {@code renamePacket} fields {@code r0..r{count-1}}, each renaming to its alias. */
    static String aliasedRenames(String packetId, int count) {
        StringBuilder query = new StringBuilder("mutation {");
        for (int i = 0; i < count; i++) {
            query.append(" r").append(i).append(": renamePacket(id: \"").append(packetId)
                    .append("\", name: \"").append(NAME_PREFIX).append("r").append(i).append("\") { id }");
        }
        return query.append(" }").toString();
    }

    MvcResult graphQl(RequestPostProcessor caller, String ip, String document) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("query", document);
        return mvc.perform(post(GRAPHQL).with(caller).with(from(ip)).contentType(json()).content(body.toString()))
                .andReturn();
    }

    /** The parsed response of a GraphQL request that the coarse HTTP guard let through (HTTP 200). */
    JsonObject graphQlOk(RequestPostProcessor caller, String ip, String document) throws Exception {
        MvcResult result = graphQl(caller, ip, document);
        assertThat(result.getResponse().getStatus()).as("HTTP status of %s", document).isEqualTo(200);
        return gson.fromJson(result.getResponse().getContentAsString(), JsonObject.class);
    }

    static List<JsonObject> errors(JsonObject response) {
        List<JsonObject> errors = new ArrayList<>();
        JsonElement array = response.get("errors");
        if (array instanceof JsonArray list) {
            list.forEach(e -> errors.add(e.getAsJsonObject()));
        }
        return errors;
    }

    static String classification(JsonObject error) {
        JsonObject extensions = error.getAsJsonObject("extensions");
        return extensions == null || !extensions.has("classification")
                ? null : extensions.get("classification").getAsString();
    }

    static List<JsonObject> rateLimited(JsonObject response) {
        return errors(response).stream().filter(e -> "RATE_LIMITED".equals(classification(e))).toList();
    }

    static String pathOf(JsonObject error) {
        return error.getAsJsonArray("path").get(0).getAsString();
    }

    /** No errors at all. */
    static void assertNoErrors(JsonObject response) {
        assertThat(errors(response)).as("errors of %s", response).isEmpty();
    }

    /** Exactly one RATE_LIMITED field error for the policy, with the HTTP body's fields as extensions. */
    static JsonObject assertFieldRateLimited(JsonObject response, String policy) {
        List<JsonObject> limited = rateLimited(response);
        assertThat(limited).as("RATE_LIMITED errors of %s", response).hasSize(1);
        JsonObject extensions = limited.getFirst().getAsJsonObject("extensions");
        assertThat(extensions.get("error").getAsString()).isEqualTo("rate_limited");
        assertThat(extensions.get("policy").getAsString()).isEqualTo(policy);
        assertThat(extensions.get("retryAfterSeconds").getAsLong()).isPositive();
        assertThat(extensions.has("message")).isFalse();
        return limited.getFirst();
    }
}

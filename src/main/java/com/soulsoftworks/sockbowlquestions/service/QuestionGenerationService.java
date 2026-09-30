
package com.soulsoftworks.sockbowlquestions.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.soulsoftworks.sockbowlquestions.ai.AiGenerationGuard;
import com.soulsoftworks.sockbowlquestions.ai.AiPermit;
import com.soulsoftworks.sockbowlquestions.quota.ContentQuotaGuard;
import com.soulsoftworks.sockbowlquestions.config.AiSecurityProperties;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.service.strategy.QuestionGenerationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Main service for question generation.
 * Delegates to different strategies based on configuration.
 *
 * <p>M4 (D11, plan m4-limits section 2.6): every generation entry point here runs
 * inside an {@link AiGenerationGuard} permit, so the rate limit, the concurrency
 * lock, the per-user quota and the global server-key budget apply to the REST
 * endpoint and to GraphQL {@code generateAndAddTossup} alike. A generation that
 * throws is refunded when its permit closes.
 */
@Service
@Slf4j
public class QuestionGenerationService {

    private final QuestionGenerationStrategy activeStrategy;
    private final String strategyName;
    private final AiGenerationGuard aiGenerationGuard;
    private final AiSecurityProperties aiProperties;
    private final ContentQuotaGuard contentQuotaGuard;
    private final PacketRepository packetRepository;

    public QuestionGenerationService(
            @Qualifier("defaultStrategy") QuestionGenerationStrategy defaultStrategy,
            AiGenerationGuard aiGenerationGuard,
            AiSecurityProperties aiProperties,
            ContentQuotaGuard contentQuotaGuard,
            PacketRepository packetRepository) {
        this.activeStrategy = defaultStrategy;
        this.aiGenerationGuard = aiGenerationGuard;
        this.aiProperties = aiProperties;
        this.contentQuotaGuard = contentQuotaGuard;
        this.packetRepository = packetRepository;
        this.strategyName = defaultStrategy.getStrategyName();

        log.info("QuestionGenerationService initialized with strategy: {} ({})",
                strategyName, activeStrategy.getClass().getSimpleName());
    }

    /**
     * Generate a complete packet of questions using the active strategy.
     *
     * @param topic Topic for the packet
     * @param additionalContext Additional context or instructions
     * @param difficulty Target difficulty (null for none); set on the packet and put in every prompt
     * @param questionCount Number of tossups/bonuses to generate (overrides default)
     * @param generateBonuses Whether to generate bonuses (default true)
     * @param requestContext Request context containing optional custom API key and model
     * @param ownerId Keycloak sub of the requesting user, null if anonymous (auth disabled)
     * @param ownerDisplayName preferred_username of the requesting user, null if anonymous
     * @return Generated packet
     * @throws JsonProcessingException if JSON processing fails
     */
    public Packet generatePacket(String topic, String additionalContext, Difficulty difficulty, int questionCount, boolean generateBonuses,
                                  AiRequestContext requestContext, String ownerId, String ownerDisplayName) throws JsonProcessingException {
        validatePrompt(topic, additionalContext);
        log.info("Generating packet using strategy: {} with {} questions (bonuses: {})", strategyName, questionCount, generateBonuses);
        // FIX3-Q item 1: an unlocked pre-check of the packets-owned quota, BEFORE
        // acquiring the AI permit, so a caller already at the limit gets 429
        // quota_exceeded without burning an ai-generate rate token, the AI
        // concurrency lock, or an ai.generations quota charge. This can race (it's
        // the same plain read-then-check ContentQuotaGuard.checkPacketsOwned always
        // was), so it is an optimization, not the enforcing check: that happens
        // again, under the lock, immediately before the save below.
        contentQuotaGuard.checkPacketsOwned(ownerId);
        // M4-UQ-01 / Q-V1-01: the AI concurrency lock (ai:inflight:{sub}, D11) is
        // acquired next, exactly as before this fix: it already admits at most one
        // generation per owner at a time, fast-rejecting (429 ai-concurrency, no
        // spin-wait) a second truly-overlapping call instead of queueing it, which
        // AiGenerationLimitsIT depends on.
        try (AiPermit permit = aiGenerationGuard.acquire(requestContext)) {
            // FIX3-Q item 1: the (possibly slow, real-world multi-second) AI call
            // runs OUTSIDE the packets-owned lock. The lock's TTL is only 10s and
            // must never be held across work that can outlast it — a caller whose
            // lock silently expired mid-generation could let a second creator in,
            // or fail its own compare-and-delete release. The strategy builds the
            // Packet but does not persist it (see its Javadoc); only the fast save
            // below is serialized per owner, re-checking the count right before it
            // commits so a slow generation still can't overshoot the quota even if
            // another caller filled it while this one was generating.
            Packet packet = activeStrategy.generatePacket(topic, additionalContext, difficulty, questionCount, generateBonuses,
                    requestContext, ownerId, ownerDisplayName);
            Packet saved = contentQuotaGuard.withPacketsOwnedSlot(ownerId, () -> packetRepository.save(packet));
            permit.success((long) questionCount * (generateBonuses ? 2 : 1));
            return saved;
        }
    }

    /**
     * Generate a single tossup using the active strategy.
     *
     * @param topic Topic for the question
     * @param additionalContext Additional context or instructions
     * @param difficulty Target difficulty, or null
     * @param existingTossups Previously generated tossups to avoid duplicates
     * @param requestContext Request context containing optional custom API key and model
     * @return Generated tossup
     */
    public Tossup generateTossup(String topic, String additionalContext, Difficulty difficulty, List<Tossup> existingTossups, AiRequestContext requestContext) {
        validatePrompt(topic, additionalContext);
        log.info("Generating tossup using strategy: {}", strategyName);
        try (AiPermit permit = aiGenerationGuard.acquire(requestContext)) {
            Tossup tossup = activeStrategy.generateTossup(topic, additionalContext, difficulty, existingTossups, requestContext);
            if (tossup != null) {
                permit.success(1);
            }
            return tossup;
        }
    }

    /**
     * Rejects prompt text over the configured lengths before anything is charged
     * ({@code sockbowl.ai.max-topic-length} / {@code max-context-length}).
     *
     * @throws InvalidApiRequestException when the topic is blank or either field is too long
     */
    public void validatePrompt(String topic, String additionalContext) {
        if (topic == null || topic.isBlank()) {
            throw new InvalidApiRequestException("Topic is required");
        }
        if (topic.length() > aiProperties.getMaxTopicLength()) {
            throw new InvalidApiRequestException(String.format(
                    "Topic cannot exceed %d characters (got %d)", aiProperties.getMaxTopicLength(), topic.length()));
        }
        if (additionalContext != null && additionalContext.length() > aiProperties.getMaxContextLength()) {
            throw new InvalidApiRequestException(String.format(
                    "Additional context cannot exceed %d characters (got %d)",
                    aiProperties.getMaxContextLength(), additionalContext.length()));
        }
    }

    /**
     * Get the currently active strategy name.
     *
     * @return Strategy name
     */
    public String getActiveStrategyName() {
        return strategyName;
    }

    /**
     * Get the active strategy instance.
     *
     * @return Active strategy
     */
    public QuestionGenerationStrategy getActiveStrategy() {
        return activeStrategy;
    }

}
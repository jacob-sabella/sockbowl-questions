package com.soulsoftworks.sockbowlquestions.service.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.soulsoftworks.sockbowlquestions.config.AiPrompts;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Default question generation strategy - the original approach.
 * Generates question and answer together using a single LLM call per tossup.
 */
@Component("defaultStrategy")
@Slf4j
public class DefaultQuestionGenerationStrategy implements QuestionGenerationStrategy {

    private final ChatClient chatClient;
    private final AiPrompts aiPrompts;
    private final PacketRepository packetRepository;

    public DefaultQuestionGenerationStrategy(
            ChatClient chatClient,
            AiPrompts aiPrompts,
            PacketRepository packetRepository) {
        this.chatClient = chatClient;
        this.aiPrompts = aiPrompts;
        this.packetRepository = packetRepository;
    }

    private record TossupPromptDTO(String question, String answer) {
    }

    @Override
    public String getStrategyName() {
        return "default";
    }

    @Override
    public Packet generatePacket(String topic, String additionalContext, int questionCount) throws JsonProcessingException {
        log.info("=== Starting New Packet Generation (Default Strategy) ===");
        log.info("Topic: {}", topic);
        log.info("Additional Context: {}", additionalContext);
        log.info("Target number of tossups: {}", questionCount);

        Packet.PacketBuilder packetBuilder = Packet
                .builder()
                .name("Generated Packet: %s - %s".formatted(topic, UUID.randomUUID()));

        List<Tossup> existingTossups = new ArrayList<>();

        for (int i = 0; i < questionCount; i++) {
            log.info("=== Generating Tossup {} of {} ===", i + 1, questionCount);
            log.info("Current topic: {}", topic);
            log.info("Number of existing tossups to avoid: {}", existingTossups.size());

            Tossup tossup = null;
            int maxAttempts = 3;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                log.info("Attempt {} to generate non-duplicate tossup", attempt);
                tossup = generateTossup(topic, additionalContext, existingTossups);

                if (existingTossups.isEmpty()) {
                    break;
                }
            }

            existingTossups.add(tossup);

            ContainsTossup containsTossup = ContainsTossup.builder()
                    .order(i + 1)
                    .tossup(tossup)
                    .build();

            packetBuilder.tossup(containsTossup);
        }

        Packet packet = packetBuilder.build();
        packetRepository.save(packet);

        return packet;
    }

    @Override
    public Tossup generateTossup(String topic, String additionalContext, List<Tossup> existingTossups) {
        log.info("=== Starting Tossup Generation (Default Strategy) ===");
        log.info("Topic: {}", topic);
        log.info("Additional Context: {}", additionalContext);
        log.info("Question number: {}", existingTossups.size() + 1);

        // Build Enhanced Prompt using Structured Input
        log.info("Building structured prompt incorporating best practices");

        String[] diversityMandates = {
                "Focus on an OBSCURE or LESSER-KNOWN example that specialists would appreciate",
                "Choose a subject from an UNEXPECTED time period or era within this theme",
                "Explore a TECHNICAL or SPECIALIZED aspect that goes beyond surface-level knowledge",
                "Select something from a DIFFERENT geographic region or cultural context than typical examples",
                "Focus on an INTERDISCIPLINARY connection or surprising relationship",
                "Choose a MODERN or CONTEMPORARY example if the theme allows",
                "Explore a FOUNDATIONAL or HISTORICAL aspect that shaped this theme",
                "Focus on an ARTISTIC, CREATIVE, or AESTHETIC dimension",
                "Choose something CONTROVERSIAL, DEBATED, or with COMPETING INTERPRETATIONS",
                "Explore a PRACTICAL APPLICATION, REAL-WORLD IMPACT, or CONCRETE EXAMPLE"
        };

        int questionIndex = existingTossups.size();
        String diversityMandate = diversityMandates[questionIndex % diversityMandates.length];

        Map<String, Object> promptParams = new HashMap<>();
        promptParams.put("tossup_number", existingTossups.size() + 1);
        promptParams.put("total_questions", 20); // Default
        promptParams.put("tossup_topic", topic);
        promptParams.put("user_context", additionalContext != null ? additionalContext : "");
        promptParams.put("diversity_mandate", diversityMandate);

        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(aiPrompts.getNaqtWriterPacketGenerationPrompt());

        log.info("Generated structured prompt: {}", systemPromptTemplate.render(promptParams));

        // Retry up to 10 times if JSON parsing fails
        int maxRetries = 10;
        TossupPromptDTO response = null;
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("LLM call attempt {} of {}", attempt, maxRetries);

                String rawResponse = chatClient.prompt()
                        .user(systemPromptTemplate.render(promptParams))
                        .call()
                        .content();

                log.info("Raw AI response (attempt {}): {}", attempt, rawResponse);

                String extractedJson = extractJson(rawResponse);
                log.info("Extracted JSON: {}", extractedJson);

                String sanitizedResponse = sanitizeJsonNewlines(extractedJson);
                log.info("Sanitized response: {}", sanitizedResponse);

                response = objectMapper.readValue(sanitizedResponse, TossupPromptDTO.class);

                log.info("Successfully parsed JSON on attempt {}", attempt);
                break;

            } catch (Exception e) {
                log.warn("Attempt {} failed to parse JSON: {}", attempt, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("Failed to parse JSON after {} attempts", maxRetries);
                    throw new RuntimeException("Failed to parse AI response after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
                log.info("Retrying...");
            }
        }

        if (response == null) {
            throw new RuntimeException("Failed to generate valid tossup after " + maxRetries + " attempts");
        }

        log.info("Received AI response:");
        log.info("Question length: {} characters", response.question() != null ? response.question().length() : 0);
        log.info("Answer length: {} characters", response.answer() != null ? response.answer().length() : 0);
        log.info("Answer: {}", response.answer());

        Objects.requireNonNull(response, "AI response DTO cannot be null");
        Objects.requireNonNull(response.question(), "Generated question text cannot be null");
        Objects.requireNonNull(response.answer(), "Generated answer text cannot be null");

        return Tossup.builder()
                .question(response.question())
                .answer(response.answer())
                .build();
    }

    /**
     * Extracts JSON from LLM response, handling cases where it's wrapped in markdown or has commentary
     */
    private String extractJson(String response) {
        int startIndex = response.indexOf('{');
        int endIndex = response.lastIndexOf('}');

        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return response.substring(startIndex, endIndex + 1);
        }

        return response;
    }

    /**
     * Sanitizes JSON by fixing common LLM JSON formatting issues
     */
    private String sanitizeJsonNewlines(String json) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
                result.append(c);
                continue;
            }

            if (inString) {
                if (c == '\n') {
                    result.append("\\n");
                    continue;
                }
                if (c == '\r') {
                    continue;
                }

                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '/' ||
                        next == 'b' || next == 'f' || next == 'n' ||
                        next == 'r' || next == 't' || next == 'u') {
                        result.append(c);
                    } else {
                        continue;
                    }
                } else {
                    result.append(c);
                }
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }
}

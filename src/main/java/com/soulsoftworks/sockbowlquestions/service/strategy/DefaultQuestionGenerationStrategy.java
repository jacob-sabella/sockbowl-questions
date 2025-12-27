package com.soulsoftworks.sockbowlquestions.service.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.soulsoftworks.sockbowlquestions.config.AiPrompts;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
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

    private record BonusPromptDTO(
            String preamble,
            String part_a_question,
            String part_a_answer,
            String part_b_question,
            String part_b_answer,
            String part_c_question,
            String part_c_answer
    ) {
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
        log.info("Will also generate {} bonuses", questionCount);

        Packet.PacketBuilder packetBuilder = Packet
                .builder()
                .name("Generated Packet: %s - %s".formatted(topic, UUID.randomUUID()));

        List<Tossup> existingTossups = new ArrayList<>();
        List<Bonus> existingBonuses = new ArrayList<>();

        // Generate tossups
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

        // Generate bonuses
        List<ContainsBonus> bonusList = new ArrayList<>();
        for (int i = 0; i < questionCount; i++) {
            log.info("=== Generating Bonus {} of {} ===", i + 1, questionCount);
            log.info("Current topic: {}", topic);
            log.info("Number of existing bonuses to avoid: {}", existingBonuses.size());

            Bonus bonus = null;
            int maxAttempts = 3;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                log.info("Attempt {} to generate non-duplicate bonus", attempt);
                bonus = generateBonus(topic, additionalContext, existingBonuses, existingTossups);

                if (existingBonuses.isEmpty()) {
                    break;
                }
            }

            existingBonuses.add(bonus);

            ContainsBonus containsBonus = new ContainsBonus(i + 1, bonus);
            bonusList.add(containsBonus);
        }

        Packet packet = packetBuilder
                .bonuses(bonusList)
                .build();
        packetRepository.save(packet);

        log.info("=== Packet Generation Complete ===");
        log.info("Generated {} tossups and {} bonuses", existingTossups.size(), existingBonuses.size());

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
        promptParams.put("total_questions", 20);
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

    @Override
    public Bonus generateBonus(String topic, String additionalContext, List<Bonus> existingBonuses, List<Tossup> existingTossups) {
        log.info("=== Starting Bonus Generation (Default Strategy) ===");
        log.info("Topic: {}", topic);
        log.info("Additional Context: {}", additionalContext);
        log.info("Bonus number: {}", existingBonuses.size() + 1);

        // Build Enhanced Prompt using Structured Input
        log.info("Building structured prompt incorporating best practices");

        String[] diversityMandates = {
                "Focus on an OBSCURE or LESSER-KNOWN thematic connection that specialists would appreciate",
                "Choose a theme from an UNEXPECTED time period or era",
                "Explore a TECHNICAL or SPECIALIZED aspect that goes beyond surface-level knowledge",
                "Select a theme from a DIFFERENT geographic region or cultural context than typical examples",
                "Focus on an INTERDISCIPLINARY connection or surprising relationship",
                "Choose a MODERN or CONTEMPORARY theme if the topic allows",
                "Explore a FOUNDATIONAL or HISTORICAL aspect that shaped this topic",
                "Focus on an ARTISTIC, CREATIVE, or AESTHETIC dimension",
                "Choose something CONTROVERSIAL, DEBATED, or with COMPETING INTERPRETATIONS",
                "Explore a PRACTICAL APPLICATION, REAL-WORLD IMPACT, or CONCRETE EXAMPLE"
        };

        int bonusIndex = existingBonuses.size();
        String diversityMandate = diversityMandates[bonusIndex % diversityMandates.length];

        // Build context about existing tossups to avoid overlap
        StringBuilder tossupContext = new StringBuilder();
        if (!existingTossups.isEmpty()) {
            tossupContext.append("\n\n**Context from existing tossups in this packet:**\n");
            tossupContext.append("The following tossup answers have already been used: ");
            for (int i = 0; i < Math.min(existingTossups.size(), 5); i++) {
                if (i > 0) tossupContext.append(", ");
                tossupContext.append(existingTossups.get(i).getAnswer());
            }
            tossupContext.append("\nAvoid directly repeating these as bonus answers, but you may explore related themes.");
        }

        Map<String, Object> promptParams = new HashMap<>();
        promptParams.put("bonus_number", existingBonuses.size() + 1);
        promptParams.put("total_bonuses", 20); // Default
        promptParams.put("bonus_topic", topic);
        promptParams.put("user_context", additionalContext != null ? additionalContext : "");
        promptParams.put("diversity_mandate", diversityMandate);
        promptParams.put("tossup_context", tossupContext.toString());

        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(aiPrompts.getNaqtWriterBonusGenerationPrompt());

        log.info("Generated structured prompt: {}", systemPromptTemplate.render(promptParams));

        // Retry up to 10 times if JSON parsing fails
        int maxRetries = 10;
        BonusPromptDTO response = null;
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

                response = objectMapper.readValue(sanitizedResponse, BonusPromptDTO.class);

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
            throw new RuntimeException("Failed to generate valid bonus after " + maxRetries + " attempts");
        }

        log.info("Received AI response:");
        log.info("Preamble length: {} characters", response.preamble() != null ? response.preamble().length() : 0);
        log.info("Part A Question: {}", response.part_a_question());
        log.info("Part A Answer: {}", response.part_a_answer());
        log.info("Part B Question: {}", response.part_b_question());
        log.info("Part B Answer: {}", response.part_b_answer());
        log.info("Part C Question: {}", response.part_c_question());
        log.info("Part C Answer: {}", response.part_c_answer());

        Objects.requireNonNull(response, "AI response DTO cannot be null");
        Objects.requireNonNull(response.preamble(), "Generated preamble cannot be null");
        Objects.requireNonNull(response.part_a_question(), "Part A question cannot be null");
        Objects.requireNonNull(response.part_a_answer(), "Part A answer cannot be null");
        Objects.requireNonNull(response.part_b_question(), "Part B question cannot be null");
        Objects.requireNonNull(response.part_b_answer(), "Part B answer cannot be null");
        Objects.requireNonNull(response.part_c_question(), "Part C question cannot be null");
        Objects.requireNonNull(response.part_c_answer(), "Part C answer cannot be null");

        // Create bonus parts
        BonusPart partA = new BonusPart();
        partA.setQuestion(response.part_a_question());
        partA.setAnswer(response.part_a_answer());

        BonusPart partB = new BonusPart();
        partB.setQuestion(response.part_b_question());
        partB.setAnswer(response.part_b_answer());

        BonusPart partC = new BonusPart();
        partC.setQuestion(response.part_c_question());
        partC.setAnswer(response.part_c_answer());

        // Create bonus with parts
        Bonus bonus = new Bonus();
        bonus.setPreamble(response.preamble());
        bonus.setBonusParts(Arrays.asList(
                new HasBonusPart(1, partA),
                new HasBonusPart(2, partB),
                new HasBonusPart(3, partC)
        ));

        return bonus;
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

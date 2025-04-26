
package com.soulsoftworks.sockbowlquestions.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.soulsoftworks.sockbowlquestions.config.AiPrompts;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

import java.util.stream.Collectors;

@Service
@Slf4j
public class QuestionGenerationService {
    private final ChatClient chatClient;
    private final AiPrompts aiPrompts;
    private final PacketRepository packetRepository;

    public QuestionGenerationService(ChatClient chatClient, AiPrompts aiPrompts, PacketRepository packetRepository) {
        this.chatClient = chatClient;
        this.aiPrompts = aiPrompts;
        this.packetRepository = packetRepository;
    }

    private record TossupPromptDTO(String question, String answer) {
    }

    public Packet generatePacket(String topic, String additionalContext) throws JsonProcessingException {

        log.info("=== Starting New Packet Generation ===");
        log.info("Topic: {}", topic);
        log.info("Additional Context: {}", additionalContext);
        log.info("Target number of tossups: 10");

        Packet.PacketBuilder packetBuilder = Packet
                .builder()
                .name("Generated Packet: %s  - %s".formatted(topic, UUID.randomUUID()));

        List<Tossup> existingTossups = new ArrayList<>();

        for (int i = 1; i <= 5; i++) {
            log.info("=== Generating Tossup {} of 10 ===", i + 1);
            log.info("Current topic: {}", topic);
            log.info("Number of existing tossups to avoid: {}", existingTossups.size());

            Tossup tossup = null;
            int maxAttempts = 3; // Maximum attempts to get a non-duplicate answer

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                log.info("Attempt {} to generate non-duplicate tossup", attempt);
                tossup = generateTossup(">" + topic + " question " + i, additionalContext, existingTossups);

                if (existingTossups.isEmpty()) {
                    break;
                }
            }

            existingTossups.add(tossup);

            ContainsTossup containsTossup = ContainsTossup.builder()
                    .order(i)
                    .tossup(tossup)
                    .build();

            packetBuilder.tossup(containsTossup);
        }

        Packet packet = packetBuilder.build();

        packetRepository.save(packet);

        return packet;
    }

    public Tossup generateTossup(String prompt, String additionalContext, List<Tossup> existingTossups) {
        log.info("=== Starting Tossup Generation ===");
        log.info("Base Prompt: {}", prompt);
        log.info("Additional Context: {}", additionalContext);
        log.info("Number of existing tossups to avoid: {}", existingTossups.size());

        // --- Build Enhanced Prompt using Structured Input ---
        log.info("Building structured prompt incorporating best practices");

        Map<String, Object> promptParams = new HashMap<>();
        promptParams.put("tossup_number", existingTossups.size() + 1);
        promptParams.put("tossup_topic", prompt);
        promptParams.put("user_context", additionalContext);
        promptParams.put("existing_tossups", existingTossups.stream()
                .map(tossup -> "DO NOT INCLUDE: " + tossup.getAnswer())
                .collect(Collectors.joining("\n")));

        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(aiPrompts.getNaqtWriterPacketGenerationPrompt());

        log.info("Generated structured prompt: {}", systemPromptTemplate.render(promptParams));

        TossupPromptDTO response = chatClient.prompt()
                .user(systemPromptTemplate.render(promptParams))
                .call()
                .entity(TossupPromptDTO.class);

        log.info("Received AI response:");
        log.info("Question length: {} characters", response.question() != null ? response.question().length() : 0);
        log.info("Answer length: {} characters", response.answer() != null ? response.answer().length() : 0);
        log.info("Answer: {}", response.answer());

        // Ensure response is not null before building Tossup
        Objects.requireNonNull(response, "AI response DTO cannot be null");
        Objects.requireNonNull(response.question(), "Generated question text cannot be null");
        Objects.requireNonNull(response.answer(), "Generated answer text cannot be null");

        return Tossup.builder()
                .question(response.question())
                .answer(response.answer())
                .build();
    }

}
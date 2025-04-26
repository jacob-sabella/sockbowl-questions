package com.soulsoftworks.sockbowlquestions.api;

import com.google.gson.Gson;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.service.QuestionGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for testing the Quizbowl packet generation functionality.
 * Provides endpoints to generate and validate quizbowl packets.
 */
@RestController
@RequestMapping("/api/packets")
public class PacketGenerationController {
    private static final Logger logger = LoggerFactory.getLogger(PacketGenerationController.class);

    private final QuestionGenerationService questionGenerationService;

    public PacketGenerationController(QuestionGenerationService questionGenerationService) {
        this.questionGenerationService = questionGenerationService;
    }

    /**
     * Generates a complete quizbowl packet.
     *
     * @return ResponseEntity containing the generated packet as text
     */
    @GetMapping(path = "generate", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> generatePacket(String topic, String additionalContext) {
        logger.info("Request received to generate a quizbowl packet");
        try {
            Packet generatedPacket = questionGenerationService.generatePacket(topic, additionalContext);
            logger.info("Successfully generated packet");
            return ResponseEntity.ok(new Gson().toJson(generatedPacket));
        } catch (Exception e) {
            logger.error("Error generating packet", e);
            return ResponseEntity.internalServerError().body("Error generating packet: " + e.getMessage());
        }
    }

}
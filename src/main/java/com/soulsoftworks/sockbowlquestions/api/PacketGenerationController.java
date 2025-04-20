package com.soulsoftworks.sockbowlquestions.api;

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
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> generatePacket() {
        logger.info("Request received to generate a quizbowl packet");
        try {
            String generatedPacket = questionGenerationService.generatePacket();
            logger.info("Successfully generated packet");
            return ResponseEntity.ok(generatedPacket);
        } catch (Exception e) {
            logger.error("Error generating packet", e);
            return ResponseEntity.internalServerError().body("Error generating packet: " + e.getMessage());
        }
    }

    /**
     * Validates a user-provided quizbowl packet.
     *
     * @param packet The packet content to validate
     * @return ResponseEntity with validation result
     */
    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validatePacket(@RequestBody String packet) {
        logger.info("Request received to validate a quizbowl packet");
        try {
            boolean isValid = questionGenerationService.validatePacket(packet);
            ValidationResult result = new ValidationResult(isValid,
                    isValid ? "Packet is valid and follows quizbowl standards" : "Packet has validation issues");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error validating packet", e);
            return ResponseEntity.internalServerError()
                    .body(new ValidationResult(false, "Error during validation: " + e.getMessage()));
        }
    }

    @GetMapping("/ui")
    public String packetGeneratorUi() {
        return "packet-generator";
    }


    /**
     * Simple DTO to represent packet validation results.
     */
    public record ValidationResult(boolean valid, String message) {}
}
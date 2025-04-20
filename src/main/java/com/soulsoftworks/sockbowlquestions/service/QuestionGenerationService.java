package com.soulsoftworks.sockbowlquestions.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class QuestionGenerationService {
    private static final Logger logger = LoggerFactory.getLogger(QuestionGenerationService.class);

    private final ChatClient chatClient;

    public QuestionGenerationService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String generatePacket() {
        logger.info("Starting generation of a full quizbowl packet");

        // Define the prompt for generating a complete packet
        String prompt = "Generate a complete quizbowl packet with 20 tossup questions " +
                "covering a diverse range of academic subjects including history, literature, " +
                "science, fine arts, and social sciences. Each question should follow standard " +
                "quizbowl format with pyramidal structure.";

        // Use the AI client to generate the packet
        String generatedPacket = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        logger.info("Successfully generated a quizbowl packet");
        return generatedPacket;
    }

    public String generateQuestion(String prompt, List<String> existingAnswers) {
        logger.info("Generating a question based on prompt: {} with {} existing answers to avoid",
                prompt, existingAnswers.size());

        // Create a comprehensive prompt that includes the context
        StringBuilder enhancedPrompt = new StringBuilder();
        enhancedPrompt.append("Generate a single pyramidal quizbowl tossup question about: ")
                .append(prompt)
                .append("\n\nEnsure the question follows the standard quizbowl format with decreasing difficulty clues.");

        // Add constraint about avoiding existing answers
        if (!existingAnswers.isEmpty()) {
            enhancedPrompt.append("\n\nAvoid using any of these answers, as they already exist in our database: ");
            enhancedPrompt.append(String.join(", ", existingAnswers));
        }

        // Use the AI client to generate the question
        String generatedQuestion = chatClient.prompt()
                .user(enhancedPrompt.toString())
                .call()
                .content();

        logger.info("Successfully generated question for prompt: {}", prompt);
        return generatedQuestion;
    }

    public boolean validatePacket(String packet) {
        logger.info("Validating quizbowl packet");

        // Define validation criteria
        Map<String, Boolean> validationCriteria = new HashMap<>();
        validationCriteria.put("hasProperFormat", true);
        validationCriteria.put("hasDiverseTopics", true);
        validationCriteria.put("hasAppropriateLength", true);
        validationCriteria.put("hasPyramidalStructure", true);

        // Create a prompt for validation
        String validationPrompt = "Validate the following quizbowl packet. Check for:\n" +
                "1. Proper formatting (clear distinction between questions)\n" +
                "2. Diverse topic coverage (multiple academic subjects)\n" +
                "3. Appropriate length for each question\n" +
                "4. Pyramidal structure with decreasing difficulty\n\n" +
                "Packet to validate:\n" + packet;

        // Use the AI client to validate the packet
        String validationResponse = chatClient.prompt()
                .user(validationPrompt)
                .call()
                .content();

        // Process validation response (in a real implementation, this would parse the AI response)
        // For now, we'll use a simple check to see if there are major issues
        assert validationResponse != null;
        boolean isValid = !validationResponse.toLowerCase().contains("major issue") &&
                !validationResponse.toLowerCase().contains("invalid format");

        logger.info("Packet validation result: {}", isValid);
        return isValid;
    }

    public String fetchSuggestedQuestions(String topic) {
        logger.info("Fetching suggested questions for topic: {}", topic);

        // Create a prompt to get question suggestions
        String suggestionsPrompt = "Suggest 5 potential quizbowl tossup question ideas related to the topic: " +
                topic + ". For each suggestion, provide a brief description of the question focus and " +
                "potential answer.";

        // Use the AI client to get suggestions
        String suggestions = chatClient.prompt()
                .advisors(advisor -> advisor.param("chat_memory_conversation_id", "suggestions")
                        .param("chat_memory_response_size", 50))
                .user(suggestionsPrompt)
                .call()
                .content();

        logger.info("Successfully fetched suggested questions for topic: {}", topic);
        return suggestions;
    }
}
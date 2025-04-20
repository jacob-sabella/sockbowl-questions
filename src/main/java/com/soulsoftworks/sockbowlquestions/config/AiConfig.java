package com.soulsoftworks.sockbowlquestions.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration class for AI-related components.
 * Provides a configured ChatClient for use throughout the application.
 */
@Configuration
public class AiConfig {
    private static final Logger logger = LoggerFactory.getLogger(AiConfig.class);

    /**
     * Creates and configures a ChatClient bean with appropriate advisors and system prompts
     * for quizbowl question generation and question answering.
     *
     * @param chatClientBuilder Builder for creating the ChatClient
     * @param vectorStore VectorStore for retrieving relevant context
     * @param tools ToolCallbackProvider for AI tools integration
     * @return Configured ChatClient instance
     */
    @Bean
    public ChatClient chatClient(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            ToolCallbackProvider tools) {

        logger.info("Initializing ChatClient with quizbowl context");

        // Configure the question answer advisor with vector store integration
        QuestionAnswerAdvisor questionAnswerAdvisor = new QuestionAnswerAdvisor(
                vectorStore,
                SearchRequest.builder().build(),
                """
                Context information is below, surrounded by ---------------------
                
                ---------------------
                {question_answer_context}
                ---------------------
                
                Given the context and provided information and not prior knowledge,
                reply to the user comment. If generating quizbowl questions, ensure they follow
                standard pyramidal format with decreasing difficulty clues.
                """
        );

        // Build and return the configured ChatClient
        return chatClientBuilder
                .defaultAdvisors(List.of(questionAnswerAdvisor))
                .defaultSystem("""
                        You are a specialized quizbowl question assistant with broad knowledge on academic topics.
                        Much like an encyclopedia, you can generate and evaluate quizbowl questions.
                        You have a Neo4j instance of quizbowl data at your disposal as well as all
                        the information in your training data about everything you've ever trained on.
                        
                        When generating questions:
                        1. Follow the standard pyramidal difficulty structure (harder clues first)
                        2. Cover diverse academic topics including history, literature, science, fine arts, etc.
                        3. Ensure questions are factually accurate and properly formatted
                        4. Avoid repetition of answers when given existing answers to avoid
                        
                        When validating questions:
                        1. Check for proper pyramidal structure
                        2. Verify factual accuracy and appropriate difficulty
                        3. Assess clarity and specificity of the answer line
                        """)
                .defaultTools(tools)
                .build();
    }
}
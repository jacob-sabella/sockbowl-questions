
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
                standard NAQT pyramidal format with decreasing difficulty clues.
                """
        );

        // Build and return the configured ChatClient
        return chatClientBuilder
                .defaultAdvisors(List.of(questionAnswerAdvisor)) // Consider enabling if using vector store for generation context
                .defaultSystem("""
                        **Role:** You are an expert NAQT-style Quizbowl Question Writer. Your expertise lies in crafting high-quality, factually accurate, and stylistically compliant tossup questions for standard high school or collegiate difficulty levels.

                        **Core Task:** Generate individual tossup questions based on specific prompts, adhering strictly to NAQT standards.

                        **Guiding Principles:**
                        *   **Pyramidality:** Questions must start with harder, more obscure clues and progressively move towards easier, more well-known clues. This rewards deeper knowledge.
                        *   **Accuracy:** All factual claims within the question must be verifiable and correct.
                        *   **Clarity:** Clues should be unambiguous. The answer line must clearly define the correct answer and handle potential ambiguities.
                        *   **Conciseness:** Questions should be engaging and avoid unnecessary wordiness while maintaining the pyramidal structure.
                        *   **Formatting:** Strict adherence to NAQT formatting guidelines is mandatory.
                        *   **Fairness:** Clues should be fair and avoid trivia traps or subjective interpretations.

                        **NAQT Tossup Formatting Checklist:**
                        1.  **Structure:** Pyramidal (hardest clues first, easiest last).
                        2.  **Power Mark:** Include a single power marker (*) usually after the first sentence or approximately 1/3 through the question text, indicating the point value increase.
                        3.  **Length:** Typically 3-5 sentences, aiming for roughly 400-600 characters.
                        4.  **Content:** Cover academic subjects (History, Literature, Science, Fine Arts, RMPSS - Religion, Mythology, Philosophy, Social Sciences, Geography, Current Events, etc.).
                        5.  **Answer Line Format:**
                            *   Start with `ANSWER:` (all caps).
                            *   Provide the most specific, common name of the answer.
                            *   Use `[` and `]` for alternate acceptable answers, separated by `or`. Example: `[or alternate]`
                            *   Use `(` and `)` for non-essential clarifications or pronunciation guides.
                            *   Use `prompt on [less specific answer]` to guide moderators.
                            *   Use `do not accept [wrong answer]` or `do not prompt on [related wrong answer]` for clarity.
                            *   Example: `ANSWER: Albert Einstein [or Albert Hermann Einstein; prompt on Einstein; do not accept or prompt on "Alfred Einstein"]`
                        6.  **Factual Integrity:** Ensure all clues accurately point towards the answer.

                        **Process for User Prompts:**
                        *   Carefully analyze the requested topic and any provided context (like previous answers).
                        *   Internally plan the clue progression from obscure to recognizable.
                        *   Write the question text adhering to all guidelines.
                        *   Construct the precise answer line.
                        *   Strictly avoid topics or answers provided in the "avoid list" context.
                        *   Prioritize novelty and avoid generating questions on overly common or canonical examples unless specifically requested.
                        """)
                .defaultTools(tools)
                .build();
    }
}
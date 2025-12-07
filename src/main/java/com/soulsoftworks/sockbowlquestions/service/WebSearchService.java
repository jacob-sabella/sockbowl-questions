package com.soulsoftworks.sockbowlquestions.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Service for performing web searches using MCP (Model Context Protocol) tools.
 * MCP tools (brave_web_search, fetch) are automatically available to the ChatClient
 * via Spring AI's MCP autoconfiguration.
 */
@Service
@Slf4j
public class WebSearchService {

    private final ChatClient chatClient;

    public WebSearchService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Search the web using AI with MCP tools.
     * The AI will automatically use available MCP tools (brave_web_search, fetch) as needed.
     *
     * @param query The search query or question
     * @return AI-generated response incorporating web search results
     */
    public String searchWithAI(String query) {
        log.info("Performing AI-powered web search for: {}", query);

        try {
            String response = chatClient.prompt()
                    .user(query)
                    .call()
                    .content();

            log.info("Search completed successfully");
            return response;

        } catch (Exception e) {
            log.error("Error performing AI web search: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to perform web search: " + e.getMessage(), e);
        }
    }

    /**
     * Search the web using Brave Search via AI.
     * The AI will use the brave_web_search MCP tool automatically.
     *
     * @param query The search query
     * @return Search results formatted by AI
     */
    public String braveSearch(String query) {
        return braveSearch(query, 10);
    }

    /**
     * Search the web using Brave Search via AI.
     * The AI will use the brave_web_search MCP tool automatically.
     *
     * @param query The search query
     * @param count Number of results to return
     * @return Search results formatted by AI
     */
    public String braveSearch(String query, int count) {
        log.info("Performing Brave search via AI: query='{}', count={}", query, count);

        try {
            String prompt = String.format("""
                    Use the brave_web_search tool to search for: %s

                    Request %d results.

                    Format the results clearly with:
                    - Title and URL for each result
                    - Brief summary of content
                    """, query, Math.min(count, 20));

            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.info("Brave search completed");
            return result;

        } catch (Exception e) {
            log.error("Error performing Brave search: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to perform Brave search: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch content from a URL using the fetch MCP tool via AI.
     *
     * @param url The URL to fetch
     * @return The fetched content, summarized by AI
     */
    public String fetchUrl(String url) {
        log.info("Fetching URL via AI: {}", url);

        try {
            String prompt = String.format("""
                    Use the fetch tool to retrieve content from: %s

                    Provide a clear summary of the content.
                    """, url);

            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.info("URL fetch completed");
            return result;

        } catch (Exception e) {
            log.error("Error fetching URL: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch URL: " + e.getMessage(), e);
        }
    }

    /**
     * Get contextual information about a topic using AI with web search.
     *
     * @param topic The topic to research
     * @return Formatted context with sources
     */
    public String getWebContext(String topic) {
        log.info("Getting web context for topic: {}", topic);

        String prompt = String.format("""
                Search the web and provide comprehensive, factual information about: %s

                Include:
                1. Key facts and recent information
                2. Important details and context
                3. Sources (URLs) for verification

                Format your response with clear sections and cite your sources.
                """, topic);

        return searchWithAI(prompt);
    }

    /**
     * Search for recent or current information about a topic.
     *
     * @param topic The topic to search for
     * @return Recent information and context
     */
    public String getCurrentInfo(String topic) {
        log.info("Getting current information for: {}", topic);

        String prompt = String.format("""
                Search the web for the most recent and current information about: %s

                Focus on:
                - Latest developments
                - Recent news or updates
                - Current status or state

                Provide a concise summary with key facts.
                """, topic);

        return searchWithAI(prompt);
    }

    /**
     * Verify a fact or claim using web search.
     *
     * @param claim The fact or claim to verify
     * @return Verification result with sources
     */
    public String verifyFact(String claim) {
        log.info("Verifying fact: {}", claim);

        String prompt = String.format("""
                Search the web to verify this claim: "%s"

                Provide:
                1. Whether the claim is accurate, partially accurate, or inaccurate
                2. Relevant facts and context
                3. Credible sources

                Be objective and fact-based.
                """, claim);

        return searchWithAI(prompt);
    }
}

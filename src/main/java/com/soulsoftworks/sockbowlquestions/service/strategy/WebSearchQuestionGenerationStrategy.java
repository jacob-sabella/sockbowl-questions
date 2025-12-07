package com.soulsoftworks.sockbowlquestions.service.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Browser-based question generation strategy (uses Browser MCP for automation).
 *
 * This strategy:
 * 1. Uses Browser MCP (Playwright) to navigate and search web pages
 * 2. Generates a list of potential answers based on gathered facts
 * 3. For each answer, crafts a pyramidal NAQT-style question
 *
 * Uses Browser MCP server - full browser automation, no API keys required.
 */
@Component("webSearchStrategy")
@Slf4j
public class WebSearchQuestionGenerationStrategy implements QuestionGenerationStrategy {

    private final ChatClient chatClient;
    private final PacketRepository packetRepository;
    private final ObjectMapper objectMapper;
    private final int candidateMultiplier;

    public WebSearchQuestionGenerationStrategy(
            ChatClient chatClient,
            PacketRepository packetRepository,
            @Value("${sockbowl.ai.packetgen.candidate-multiplier:3}") int candidateMultiplier) {
        this.chatClient = chatClient;
        this.packetRepository = packetRepository;
        this.objectMapper = new ObjectMapper();
        this.candidateMultiplier = candidateMultiplier;
        log.info("Initialized WebSearchQuestionGenerationStrategy with candidate multiplier: {}", candidateMultiplier);
    }

    @Override
    public String getStrategyName() {
        return "knowledge-first";
    }

    @Override
    public Packet generatePacket(String topic, String additionalContext, int questionCount) throws JsonProcessingException {
        log.info("=== Starting Packet Generation (Knowledge-First Strategy) ===");
        log.info("Topic: {}", topic);
        log.info("Additional Context: {}", additionalContext);
        log.info("Target number of tossups: {}", questionCount);

        // STEP 1: Gather comprehensive knowledge using local LLM
        log.info("STEP 1: Generating comprehensive knowledge about: {}", topic);
        String factSources = gatherKnowledge(topic, additionalContext);
        log.info("Generated {} characters of knowledge content", factSources.length());

        // STEP 2: Iteratively generate and refine answers until they match criteria
        log.info("STEP 2: Iteratively generating {} high-quality answers matching context", questionCount);
        List<String> answers = iterativelyGenerateAnswers(topic, additionalContext, factSources, questionCount);
        log.info("Final answer set ({} answers): {}", answers.size(), answers);

        // STEP 3: For each answer, craft a question
        log.info("STEP 3: Crafting questions for each answer");
        List<Tossup> tossups = new ArrayList<>();

        for (int i = 0; i < answers.size(); i++) {
            String answer = answers.get(i);
            log.info("Crafting question {} of {} for answer: {}", i + 1, answers.size(), answer);

            Tossup tossup = craftQuestionForAnswer(topic, answer, factSources, additionalContext);
            tossups.add(tossup);
        }

        // STEP 4: Analyze for cross-references and resolve cycles
        log.info("STEP 4: Analyzing questions for cross-references and resolving any cycles");
        List<Tossup> finalTossups = resolveCrossReferencesAndCycles(tossups, topic, additionalContext, factSources, answers);

        // STEP 5: Order questions intelligently to minimize giveaways
        log.info("STEP 5: Ordering questions to minimize cross-reference giveaways");
        List<QuestionReference> finalReferences = analyzeQuestionCrossReferences(finalTossups);
        List<Tossup> orderedTossups = orderQuestionsIntelligently(finalTossups, finalReferences);

        // STEP 7: Build final packet with ordered questions
        log.info("STEP 7: Building final packet");
        Packet.PacketBuilder packetBuilder = Packet
                .builder()
                .name("Generated Packet (Knowledge-First): %s - %s".formatted(topic, UUID.randomUUID()));

        for (int i = 0; i < orderedTossups.size(); i++) {
            ContainsTossup containsTossup = ContainsTossup.builder()
                    .order(i + 1)
                    .tossup(orderedTossups.get(i))
                    .build();

            packetBuilder.tossup(containsTossup);
        }

        Packet packet = packetBuilder.build();
        packetRepository.save(packet);

        log.info("=== Packet Generation Complete ===");
        return packet;
    }

    @Override
    public Tossup generateTossup(String topic, String additionalContext, List<Tossup> existingTossups) {
        log.info("=== Generating Single Tossup (Knowledge-First Strategy) ===");
        log.info("Topic: {}", topic);

        // Gather knowledge
        String factSources = gatherKnowledge(topic, additionalContext);

        // Generate a single answer
        List<String> existingAnswers = existingTossups.stream()
                .map(Tossup::getAnswer)
                .collect(Collectors.toList());

        List<String> answers = generateAnswerList(topic, additionalContext, factSources, 1, existingAnswers);

        if (answers.isEmpty()) {
            throw new RuntimeException("Failed to generate answer for topic: " + topic);
        }

        String answer = answers.get(0);
        log.info("Generated answer: {}", answer);

        // Craft question for this answer
        return craftQuestionForAnswer(topic, answer, factSources, additionalContext);
    }

    /**
     * STEP 1: Gather knowledge using Browser MCP (Playwright automation).
     * Navigates to search engines and extracts information.
     */
    private String gatherKnowledge(String topic, String additionalContext) {
        log.info("Gathering knowledge via Browser MCP for topic: {}", topic);

        StringBuilder knowledge = new StringBuilder();

        try {
            // First: Generate smart search queries using LLM
            log.info("Generating optimized search queries for: {}", topic);
            SearchQueries searchQueries = generateSmartSearchQueries(topic, additionalContext);
            log.info("Generated search queries: {}", searchQueries);

            // Search 1: Primary optimized search
            log.info("Search 1: Primary search with query: '{}'", searchQueries.primary);
            String primaryResults = performBrowserSearch(searchQueries.primary, topic);
            knowledge.append("=== Primary Search Results ===\n").append(primaryResults).append("\n\n");

            // Search 2: Encyclopedia/Wiki search (if applicable)
            if (searchQueries.wikiQuery != null && !searchQueries.wikiQuery.isEmpty()) {
                log.info("Search 2: Encyclopedia search with query: '{}'", searchQueries.wikiQuery);
                String wikiResults = performBrowserSearch(searchQueries.wikiQuery, topic);
                knowledge.append("=== Encyclopedia/Wiki Results ===\n").append(wikiResults).append("\n\n");
            }

            // Search 3: Specialized/Technical search
            if (searchQueries.specializedQuery != null && !searchQueries.specializedQuery.isEmpty()) {
                log.info("Search 3: Specialized search with query: '{}'", searchQueries.specializedQuery);
                String specializedResults = performBrowserSearch(searchQueries.specializedQuery, topic);
                knowledge.append("=== Specialized Results ===\n").append(specializedResults).append("\n\n");
            }

            // Search 4: Context-specific search
            if (additionalContext != null && !additionalContext.isEmpty() &&
                searchQueries.contextQuery != null && !searchQueries.contextQuery.isEmpty()) {
                log.info("Search 4: Context-specific search with query: '{}'", searchQueries.contextQuery);
                String contextResults = performBrowserSearch(searchQueries.contextQuery, topic);
                knowledge.append("=== Contextual Results ===\n").append(contextResults).append("\n\n");
            }

            // Search 5 & 6: Second and third-order concept searches for diversity
            log.info("Searching for second and third-order related concepts for diversity");
            String relatedConcepts = searchRelatedConcepts(topic, additionalContext, knowledge.toString());
            if (!relatedConcepts.isEmpty()) {
                knowledge.append("=== Related Concepts (Second/Third-Order) ===\n").append(relatedConcepts).append("\n\n");
            }

        } catch (Exception e) {
            log.error("Error gathering knowledge via browser: {}", e.getMessage(), e);

            // Fallback to LLM knowledge
            log.warn("Browser search failed, falling back to LLM knowledge");
            try {
                String llmKnowledge = chatClient.prompt()
                        .user("Provide comprehensive, factual information about: " + topic +
                              "\n\nInclude diverse aspects, key facts, and notable details.")
                        .call()
                        .content();
                knowledge.append("=== LLM Fallback Knowledge ===\n").append(llmKnowledge).append("\n\n");
            } catch (Exception e2) {
                log.error("Even fallback failed: {}", e2.getMessage(), e2);
            }
        }

        return knowledge.toString();
    }

    /**
     * Record to hold multiple search query strategies
     */
    private record SearchQueries(
            String primary,
            String wikiQuery,
            String specializedQuery,
            String contextQuery
    ) {}

    /**
     * Use LLM to generate smart, optimized search queries based on the topic
     */
    private SearchQueries generateSmartSearchQueries(String topic, String additionalContext) {
        log.info("Using LLM to generate optimized search queries");

        try {
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("You are a search query optimizer for ADVANCED quiz bowl questions. Generate search queries that will find DEEP, NON-OBVIOUS information.\n\n");
            promptBuilder.append("Topic: ").append(topic).append("\n");
            if (additionalContext != null && !additionalContext.isEmpty()) {
                promptBuilder.append("Context: ").append(additionalContext).append("\n");
            }
            promptBuilder.append("\n");
            promptBuilder.append("CRITICAL GUIDANCE - AVOID THE OBVIOUS:\n");
            promptBuilder.append("- DO NOT search for the most famous/canonical examples\n");
            promptBuilder.append("- DO NOT search for basic introductory information\n");
            promptBuilder.append("- DO search for lesser-known but still notable aspects\n");
            promptBuilder.append("- DO search for specialized, technical, or niche angles\n");
            promptBuilder.append("- DO search for unexpected connections and influences\n");
            promptBuilder.append("\n");
            promptBuilder.append("Generate 3-4 optimized search queries:\n");
            promptBuilder.append("1. PRIMARY: Deep dive search avoiding the most obvious aspects (add terms like wiki, encyclopedia if encyclopedic)\n");
            promptBuilder.append("2. WIKI: Encyclopedia search for lesser-known but notable examples (or null if not applicable)\n");
            promptBuilder.append("3. SPECIALIZED: Technical, academic, or expert-level sources about non-obvious aspects\n");
            if (additionalContext != null && !additionalContext.isEmpty()) {
                promptBuilder.append("4. CONTEXT: Context-specific search emphasizing depth over breadth\n");
            }
            promptBuilder.append("\n");
            promptBuilder.append("Return as JSON with fields: primary, wiki, specialized, context\n");
            promptBuilder.append("Each field should contain a search query string or null.\n");

            String response = chatClient.prompt()
                    .user(promptBuilder.toString())
                    .call()
                    .content();

            log.info("Raw search query response: {}", response);

            // Extract and parse JSON
            String json = extractJson(response);
            Map<String, String> queries = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});

            return new SearchQueries(
                    queries.getOrDefault("primary", topic),
                    queries.get("wiki"),
                    queries.get("specialized"),
                    queries.get("context")
            );

        } catch (Exception e) {
            log.error("Failed to generate smart queries, using basic query: {}", e.getMessage());
            // Fallback to simple queries
            return new SearchQueries(
                    topic,
                    topic + " wikipedia",
                    topic + " technical details",
                    additionalContext != null ? topic + " " + additionalContext : null
            );
        }
    }

    /**
     * Perform a browser search and extract information
     */
    private String performBrowserSearch(String query, String topic) {
        try {
            String prompt = "Use the browser tools to:\n" +
                    "1. Navigate to https://duckduckgo.com\n" +
                    "2. Search for: " + query + "\n" +
                    "3. Extract the main search results (titles, snippets)\n" +
                    "4. Click on the most credible result (prefer .edu, Wikipedia, established sources)\n" +
                    "5. Extract detailed information from that page\n" +
                    "\n" +
                    "Then provide comprehensive factual information about '" + topic + "' from what you found.\n" +
                    "Focus on:\n" +
                    "- Key facts, dates, names, and specific details\n" +
                    "- Historical context and significance\n" +
                    "- Notable examples and instances\n" +
                    "- Technical or specialized information\n" +
                    "\n" +
                    "Ignore advertisements and navigation. Extract factual content only.";

            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

        } catch (Exception e) {
            log.error("Browser search failed for query '{}': {}", query, e.getMessage());
            return "Search failed for query: " + query;
        }
    }

    /**
     * Iteratively generate and refine answers until they meet quality criteria.
     * This is a recursive process that:
     * 1. Generates a large pool of candidates (3x needed)
     * 2. Evaluates each against the additional context
     * 3. If not enough good matches, searches deeper and repeats
     */
    private List<String> iterativelyGenerateAnswers(
            String topic,
            String additionalContext,
            String factSources,
            int targetCount) {

        int maxIterations = 5;
        List<String> finalAnswers = new ArrayList<>();
        StringBuilder cumulativeFacts = new StringBuilder(factSources);

        log.info("Starting iterative answer generation (multiplier: {}x)", candidateMultiplier);

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            log.info("=== Answer Generation Iteration {} of {} ===", iteration, maxIterations);

            int neededAnswers = targetCount - finalAnswers.size();
            if (neededAnswers <= 0) {
                log.info("Target answer count reached!");
                break;
            }

            // Generate candidate pool (3x what we need)
            int candidateCount = neededAnswers * candidateMultiplier;
            log.info("Generating {} answer candidates (need {} more)", candidateCount, neededAnswers);

            List<String> candidates = generateAnswerList(
                    topic,
                    additionalContext,
                    cumulativeFacts.toString(),
                    candidateCount,
                    finalAnswers // Avoid duplicates
            );

            log.info("Generated {} candidates", candidates.size());

            // Evaluate and score candidates against context
            List<EvaluatedAnswer> evaluatedCandidates = evaluateAnswers(
                    candidates,
                    topic,
                    additionalContext,
                    cumulativeFacts.toString()
            );

            // Select best matches
            List<String> selectedAnswers = selectBestAnswers(
                    evaluatedCandidates,
                    neededAnswers,
                    additionalContext
            );

            log.info("Selected {} answers from candidates", selectedAnswers.size());
            finalAnswers.addAll(selectedAnswers);

            // Check if we're satisfied with the quality
            if (finalAnswers.size() >= targetCount) {
                log.info("Successfully generated {} quality answers!", finalAnswers.size());
                break;
            }

            // If we didn't get enough good answers, dig deeper
            if (selectedAnswers.size() < neededAnswers && iteration < maxIterations) {
                log.info("Only found {} suitable answers, need {}. Searching deeper...",
                        selectedAnswers.size(), neededAnswers);

                // Generate more focused search queries based on what's missing
                String deeperSearch = performDeeperSearch(topic, additionalContext, finalAnswers);
                cumulativeFacts.append("\n\n=== Iteration ").append(iteration)
                               .append(" Deeper Search ===\n").append(deeperSearch);

                log.info("Added {} chars of deeper knowledge", deeperSearch.length());
            }
        }

        return finalAnswers;
    }

    /**
     * Record for tracking evaluated answers with scores
     */
    private record EvaluatedAnswer(
            String answer,
            double relevanceScore,
            String reasoning
    ) {}

    /**
     * Evaluate answer candidates against the additional context
     */
    private List<EvaluatedAnswer> evaluateAnswers(
            List<String> candidates,
            String topic,
            String additionalContext,
            String factSources) {

        log.info("Evaluating {} candidates against context criteria", candidates.size());

        if (additionalContext == null || additionalContext.isEmpty()) {
            // No specific context, just return all with equal scores
            return candidates.stream()
                    .map(ans -> new EvaluatedAnswer(ans, 7.0, "No specific context to match"))
                    .collect(Collectors.toList());
        }

        try {
            StringBuilder evalPrompt = new StringBuilder();
            evalPrompt.append("You are evaluating ADVANCED quiz bowl answer candidates. Be STRICT - penalize obvious/easy answers AND vague categories.\n\n");
            evalPrompt.append("Topic: ").append(topic).append("\n");
            evalPrompt.append("Required Context: ").append(additionalContext).append("\n\n");
            evalPrompt.append("Candidates to evaluate:\n");
            for (int i = 0; i < candidates.size(); i++) {
                evalPrompt.append((i + 1)).append(". ").append(candidates.get(i)).append("\n");
            }
            evalPrompt.append("\nFor EACH candidate, rate it on a scale of 0-10.\n\n");
            evalPrompt.append("SCORING CRITERIA (be STRICT):\n");
            evalPrompt.append("9-10: Excellent - Specific entity, non-obvious, matches context perfectly, requires deep knowledge\n");
            evalPrompt.append("7-8:  Good - Specific entity, matches context well, somewhat challenging\n");
            evalPrompt.append("5-6:  Acceptable - Specific enough, matches context, but fairly obvious or well-known\n");
            evalPrompt.append("3-4:  Poor - Too obvious, canonical example, weak context match, OR too vague/categorical\n");
            evalPrompt.append("0-2:  Reject - Famous/canonical example, doesn't match context, too easy, OR vague description instead of specific thing\n\n");
            evalPrompt.append("⚠️ HEAVILY PENALIZE:\n");
            evalPrompt.append("- The most famous examples (Mona Lisa, Beethoven's 5th, Hamlet, etc.)\n");
            evalPrompt.append("- Textbook canonical answers that beginners would know\n");
            evalPrompt.append("- Answers a casual enthusiast would immediately guess\n");
            evalPrompt.append("- VAGUE CATEGORIES/DESCRIPTIONS instead of specific things (e.g., 'battles in France' vs 'the Battle of Waterloo')\n");
            evalPrompt.append("- Answers that are descriptions of types of things rather than a specific named thing\n\n");
            evalPrompt.append("REWARD:\n");
            evalPrompt.append("- SPECIFIC, referenceable entities (proper nouns, defined terms)\n");
            evalPrompt.append("- Lesser-known but still notable and quiz bowl-worthy answers\n");
            evalPrompt.append("- Technical or specialized aspects requiring deeper knowledge\n");
            evalPrompt.append("- Second/third-order concepts that are SPECIFIC things\n\n");
            evalPrompt.append("RULE: If you can't write a Wikipedia article specifically about THIS THING, score it 0-2.\n\n");
            evalPrompt.append("Return as JSON array of objects.\n");
            evalPrompt.append("Each object should have fields: index (number), score (number 0-10), reasoning (string)\n");

            String response = chatClient.prompt()
                    .user(evalPrompt.toString())
                    .call()
                    .content();

            log.info("Raw evaluation response: {}", response);

            // Parse evaluation
            String json = extractJsonArray(response);
            List<Map<String, Object>> evaluations = objectMapper.readValue(
                    json,
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            List<EvaluatedAnswer> results = new ArrayList<>();
            for (Map<String, Object> eval : evaluations) {
                int index = ((Number) eval.get("index")).intValue() - 1;
                double score = ((Number) eval.get("score")).doubleValue();
                String reasoning = (String) eval.get("reasoning");

                if (index >= 0 && index < candidates.size()) {
                    results.add(new EvaluatedAnswer(candidates.get(index), score, reasoning));
                }
            }

            log.info("Evaluated {} answers", results.size());
            return results;

        } catch (Exception e) {
            log.error("Evaluation failed, returning all candidates with neutral scores: {}", e.getMessage());
            return candidates.stream()
                    .map(ans -> new EvaluatedAnswer(ans, 5.0, "Evaluation failed"))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Select the best answers based on evaluation scores
     */
    private List<String> selectBestAnswers(
            List<EvaluatedAnswer> evaluated,
            int count,
            String additionalContext) {

        double minScore = (additionalContext != null && !additionalContext.isEmpty()) ? 6.0 : 4.0;

        log.info("Selecting up to {} answers with score >= {}", count, minScore);

        return evaluated.stream()
                .filter(e -> e.relevanceScore >= minScore)
                .sorted((a, b) -> Double.compare(b.relevanceScore, a.relevanceScore))
                .limit(count)
                .peek(e -> log.info("Selected: {} (score: {}) - {}", e.answer, e.relevanceScore, e.reasoning))
                .map(EvaluatedAnswer::answer)
                .collect(Collectors.toList());
    }

    /**
     * Perform deeper, more focused search when initial candidates aren't suitable
     */
    private String performDeeperSearch(String topic, String additionalContext, List<String> existingAnswers) {
        log.info("Performing deeper search to find more suitable answers");

        try {
            // STEP 1: Have LLM analyze WHY current answers don't meet the goal
            StringBuilder analysisPrompt = new StringBuilder();
            analysisPrompt.append("TASK: Analyze why our ADVANCED quiz bowl answers don't meet the goal. Remember to AVOID OBVIOUS concepts.\n\n");
            analysisPrompt.append("TOPIC: ").append(topic).append("\n");
            if (additionalContext != null && !additionalContext.isEmpty()) {
                analysisPrompt.append("GOAL/CONTEXT: ").append(additionalContext).append("\n");
            }
            analysisPrompt.append("\nCURRENT ANSWERS WE HAVE:\n");
            if (existingAnswers.isEmpty()) {
                analysisPrompt.append("(None yet - this is our first search)\n");
            } else {
                for (String ans : existingAnswers) {
                    analysisPrompt.append("- ").append(ans).append("\n");
                }
            }
            analysisPrompt.append("\nANALYZE (with emphasis on NON-OBVIOUS angles):\n");
            analysisPrompt.append("1. What aspects of the goal/context are NOT adequately covered?\n");
            analysisPrompt.append("2. What LESSER-KNOWN but quiz bowl-worthy answers would better fulfill requirements?\n");
            analysisPrompt.append("3. What SPECIALIZED, TECHNICAL, or NICHE aspects should we explore?\n");
            analysisPrompt.append("4. Are any current answers too obvious/famous? What deeper concepts could replace them?\n");
            analysisPrompt.append("\n⚠️ Focus on finding challenging, non-obvious content that requires deep knowledge.\n");
            analysisPrompt.append("\nProvide a brief analysis explaining what's missing and why we need to search deeper.");

            String analysis = chatClient.prompt()
                    .user(analysisPrompt.toString())
                    .call()
                    .content()
                    .trim();

            log.info("LLM Analysis of gaps:\n{}", analysis);

            // STEP 2: Generate smart search query based on the gap analysis
            StringBuilder queryPrompt = new StringBuilder();
            queryPrompt.append("Based on this analysis of what we're missing:\n\n");
            queryPrompt.append(analysis).append("\n\n");
            queryPrompt.append("Generate a HIGHLY SPECIFIC web search query to find NON-OBVIOUS information filling these gaps.\n");
            queryPrompt.append("The search query should:\n");
            queryPrompt.append("- Target exactly what's missing from our current answer set\n");
            queryPrompt.append("- Find specialized, technical, or niche information\n");
            queryPrompt.append("- AVOID directing to the most famous/canonical examples\n");
            queryPrompt.append("- Look for lesser-known but quiz bowl-worthy content\n");
            queryPrompt.append("\nReturn ONLY the search query text (no explanation).");

            String searchQuery = chatClient.prompt()
                    .user(queryPrompt.toString())
                    .call()
                    .content()
                    .trim();

            log.info("Generated targeted search query based on gaps: {}", searchQuery);

            // STEP 3: Perform the deeper search
            String deeperResults = performBrowserSearch(searchQuery, topic);

            log.info("Deeper search completed, found {} chars of new information", deeperResults.length());
            return deeperResults;

        } catch (Exception e) {
            log.error("Deeper search failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * STEP 2: Generate a list of potential answers based on gathered facts.
     */
    private List<String> generateAnswerList(String topic, String additionalContext, String factSources, int count) {
        return generateAnswerList(topic, additionalContext, factSources, count, Collections.emptyList());
    }

    /**
     * STEP 2: Generate a list of potential answers based on gathered facts.
     * Avoids answers in the exclusion list.
     */
    private List<String> generateAnswerList(
            String topic,
            String additionalContext,
            String factSources,
            int count,
            List<String> answersToAvoid) {

        log.info("Generating {} answer candidates", count);

        String avoidanceContext = answersToAvoid.isEmpty()
                ? ""
                : "\n\nDo NOT generate answers for any of these (already used):\n" +
                  String.join("\n", answersToAvoid);

        String prompt = String.format("""
                Based on the following factual sources about "%s", generate a list of %d ADVANCED, NON-OBVIOUS answers
                for challenging quiz bowl tossup questions.

                %s

                FACTUAL SOURCES:
                %s

                🎯 ANSWER MUST BE A SPECIFIC, CONCRETE THING:
                ✅ GOOD - Specific entities:
                   - Proper names: "Charles Babbage", "Mount Vesuvius", "The Rite of Spring"
                   - Specific works: "Ulysses", "Guernica", "Symphony No. 9"
                   - Specific events: "the Battle of Hastings", "the July Revolution of 1830"
                   - Defined terms: "photosynthesis", "the Doppler effect", "iambic pentameter"
                   - Specific movements/groups: "the Pre-Raphaelite Brotherhood", "the Vienna Circle"

                ❌ BAD - Vague descriptions/categories:
                   - "amateur press associations devoted to anthropomorphic animals" (too vague)
                   - "novels about the American Dream" (category, not a thing)
                   - "battles involving Napoleon" (category, not specific)
                   - "paintings from the Renaissance" (too broad)

                RULE: If you can't write a Wikipedia article specifically about THIS THING (not a category of things), it's too vague.

                ⚠️ CRITICAL - AVOID OBVIOUS/EASY CONCEPTS:
                - DO NOT use the most famous, canonical, or textbook examples
                - DO NOT use answers that a casual enthusiast would immediately guess
                - DO NOT use the "greatest hits" or most well-known instances
                - Example: If topic is "Impressionism", DON'T use Monet or Renoir - use Caillebotte or Berthe Morisot
                - Example: If topic is "Civil War Battles", DON'T use Gettysburg - use Stones River or Antietam's specific phases
                - Example: If topic is "Shakespeare", DON'T use Hamlet - use Cymbeline or Pericles

                INSTEAD, PRIORITIZE:
                - Lesser-known but still significant and quiz bowl-worthy SPECIFIC answers
                - Specific technical terms or specialized concepts with clear definitions
                - Specific second and third-order entities related to the topic
                - Niche but notable NAMED/DEFINED things with rich factual detail
                - Answers that will challenge knowledgeable players, not beginners

                REQUIREMENTS:
                1. Each answer MUST be a specific, referenceable entity - NOT a vague category or description
                2. Answers should be formatted in NAQT answer line format (e.g., "ANSWER: [Main Answer] [or alternate] (with clarification)")
                3. Prioritize answers with rich, verifiable details in the sources
                4. Ensure diversity - cover different categories, time periods, and aspects of the topic
                5. Focus on answers that allow for pyramidal question construction (obscure facts -> well-known facts)
                6. Avoid answers that are too closely related or might reference each other in their questions
                   - Example: Don't include both "Romeo and Juliet" and "Juliet Capulet"
                   - Example: Don't include both "World War II" and "D-Day invasion"
                   - Choose answers that are independent and won't give each other away
                %s

                Return ONLY a JSON array of answer strings, like:
                ["ANSWER: First Answer", "ANSWER: Second Answer", ...]
                """,
                topic,
                count,
                additionalContext != null ? "Additional context: " + additionalContext : "",
                factSources,
                avoidanceContext
        );

        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Attempt {} to generate answer list", attempt);

                String response = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();

                log.info("Raw answer list response: {}", response);

                // Extract JSON array
                String extractedJson = extractJsonArray(response);
                log.info("Extracted JSON: {}", extractedJson);

                // Parse as list of strings
                List<String> answers = objectMapper.readValue(extractedJson, new TypeReference<List<String>>() {});

                log.info("Successfully parsed {} answers", answers.size());
                return answers;

            } catch (Exception e) {
                log.warn("Attempt {} failed: {}", attempt, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("Failed to generate answer list after {} attempts", maxRetries);
                    throw new RuntimeException("Failed to generate answer list: " + e.getMessage(), e);
                }
            }
        }

        return Collections.emptyList();
    }

    /**
     * STEP 3: Craft a pyramidal NAQT question for a specific answer.
     */
    private Tossup craftQuestionForAnswer(String topic, String answer, String factSources, String additionalContext) {
        log.info("Crafting question for answer: {}", answer);

        // Build prompt without String.format to avoid template parsing issues
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("You are crafting an NAQT-style pyramidal tossup question for this specific answer:\n\n");
        promptBuilder.append(answer).append("\n\n");
        promptBuilder.append("Topic context: ").append(topic).append("\n");
        if (additionalContext != null && !additionalContext.isEmpty()) {
            promptBuilder.append("Additional context: ").append(additionalContext).append("\n");
        }
        promptBuilder.append("\n");
        promptBuilder.append("Use these factual sources to construct your question:\n");
        promptBuilder.append(factSources).append("\n\n");
        promptBuilder.append("PYRAMIDAL STRUCTURE (CRITICAL - MUST FOLLOW):\n\n");
        promptBuilder.append("SENTENCE 1 (HARDEST - Experts only):\n");
        promptBuilder.append("- Use the MOST OBSCURE fact from the sources\n");
        promptBuilder.append("- Technical terms, lesser-known works, specific dates, niche details\n");
        promptBuilder.append("- Should be buzzing clue for deep experts only\n");
        promptBuilder.append("- Example level: PhD student or specialist would know\n\n");
        promptBuilder.append("POWER MARK (*): Place immediately after sentence 1\n\n");
        promptBuilder.append("SENTENCE 2-3 (MODERATE - Knowledgeable players):\n");
        promptBuilder.append("- Use RECOGNIZABLE but not obvious facts\n");
        promptBuilder.append("- Notable works, significant achievements, historical context\n");
        promptBuilder.append("- Should be buzzing range for well-read enthusiasts\n");
        promptBuilder.append("- Example level: College student who studied this area\n\n");
        promptBuilder.append("FINAL SENTENCE (EASIEST - Giveaway):\n");
        promptBuilder.append("- Use the MOST FAMOUS, CANONICAL fact\n");
        promptBuilder.append("- What everyone knows, the defining characteristic\n");
        promptBuilder.append("- Should allow even beginners to buzz with confidence\n");
        promptBuilder.append("- Example level: High school student or casual enthusiast\n\n");
        promptBuilder.append("REQUIREMENTS:\n");
        promptBuilder.append("1. STRICT pyramidal progression: Each sentence must be EASIER than the previous\n");
        promptBuilder.append("2. Write 3-5 sentences, approximately 400-600 characters total\n");
        promptBuilder.append("3. ALL FACTS MUST BE VERIFIABLE from the sources provided\n");
        promptBuilder.append("4. Power mark (*) must appear after sentence 1 (approximately 1/3 through)\n");
        promptBuilder.append("5. Test your pyramid: Could expert buzz on #1? Knowledgeable player on #2-3? Beginner on final?\n\n");
        promptBuilder.append("Return your response as a JSON object.\n");
        promptBuilder.append("The JSON should have a single field called question containing your pyramidal question text.\n");
        promptBuilder.append("Do NOT include the answer in the response - we already have it.\n");

        String prompt = promptBuilder.toString();

        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Attempt {} to craft question", attempt);

                String response = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();

                log.info("Raw question response: {}", response);

                String extractedJson = extractJson(response);
                log.info("Extracted JSON: {}", extractedJson);

                String sanitizedJson = sanitizeJsonNewlines(extractedJson);

                Map<String, String> questionObj = objectMapper.readValue(sanitizedJson, new TypeReference<Map<String, String>>() {});
                String questionText = questionObj.get("question");

                if (questionText == null || questionText.isEmpty()) {
                    throw new IllegalArgumentException("Question text is empty");
                }

                log.info("Successfully crafted question ({} characters)", questionText.length());

                return Tossup.builder()
                        .question(questionText)
                        .answer(answer)
                        .build();

            } catch (Exception e) {
                log.warn("Attempt {} failed: {}", attempt, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("Failed to craft question after {} attempts", maxRetries);
                    throw new RuntimeException("Failed to craft question for answer: " + answer, e);
                }
            }
        }

        throw new RuntimeException("Failed to craft question for answer: " + answer);
    }

    /**
     * Extract JSON object from response
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
     * Extract JSON array from response
     */
    private String extractJsonArray(String response) {
        int startIndex = response.indexOf('[');
        int endIndex = response.lastIndexOf(']');

        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return response.substring(startIndex, endIndex + 1);
        }

        return response;
    }

    /**
     * Sanitize JSON newlines
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

    /**
     * Search for second and third-order related concepts to ensure diversity
     */
    private String searchRelatedConcepts(String topic, String additionalContext, String existingKnowledge) {
        log.info("Identifying second and third-order related concepts");

        try {
            // Ask LLM to identify related concepts that are 2-3 degrees away from the main topic
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("Analyze this topic and identify LESSER-KNOWN second and third-order related concepts for ADVANCED quiz bowl questions.\n\n");
            promptBuilder.append("Topic: ").append(topic).append("\n");
            if (additionalContext != null && !additionalContext.isEmpty()) {
                promptBuilder.append("Context: ").append(additionalContext).append("\n");
            }
            promptBuilder.append("\n");
            promptBuilder.append("AVOID THE OBVIOUS:\n");
            promptBuilder.append("- Do NOT suggest the most famous related figures, works, or events\n");
            promptBuilder.append("- Do NOT suggest canonical textbook examples\n");
            promptBuilder.append("- Do NOT suggest things a casual enthusiast would immediately think of\n");
            promptBuilder.append("\n");
            promptBuilder.append("INSTEAD, FOCUS ON:\n");
            promptBuilder.append("- Second-order: Lesser-known but significant figures/works/events directly connected to the topic\n");
            promptBuilder.append("- Third-order: Unexpected influences, niche specializations, technical aspects, or peripheral developments\n");
            promptBuilder.append("- Look for interesting angles that require deeper knowledge\n");
            promptBuilder.append("\n");
            promptBuilder.append("Generate 2-3 search queries for NON-OBVIOUS second/third-order concepts.\n");
            promptBuilder.append("These should be quiz bowl-worthy but not the first things someone would think of.\n");
            promptBuilder.append("\n");
            promptBuilder.append("Return as JSON array of search query strings.\n");

            String response = chatClient.prompt()
                    .user(promptBuilder.toString())
                    .call()
                    .content();

            String json = extractJsonArray(response);
            List<String> queries = objectMapper.readValue(json, new TypeReference<List<String>>() {});

            StringBuilder relatedKnowledge = new StringBuilder();
            int count = Math.min(queries.size(), 3); // Limit to 3 additional searches

            for (int i = 0; i < count; i++) {
                String query = queries.get(i);
                log.info("Related concept search {}: {}", i + 1, query);
                String results = performBrowserSearch(query, topic);
                relatedKnowledge.append("Related Concept ").append(i + 1).append(": ").append(query).append("\n");
                relatedKnowledge.append(results).append("\n\n");
            }

            return relatedKnowledge.toString();

        } catch (Exception e) {
            log.error("Failed to search related concepts: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Resolve cross-references and cycles by regenerating problematic questions
     */
    private List<Tossup> resolveCrossReferencesAndCycles(
            List<Tossup> tossups,
            String topic,
            String additionalContext,
            String factSources,
            List<String> originalAnswers) {

        int maxAttempts = 5;
        List<Tossup> workingTossups = new ArrayList<>(tossups);
        List<String> workingAnswers = new ArrayList<>(originalAnswers);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info("Cycle resolution attempt {} of {}", attempt, maxAttempts);

            // Analyze current state
            List<QuestionReference> references = analyzeQuestionCrossReferences(workingTossups);

            // Check for cycles
            List<Integer> cyclicQuestions = findCyclicQuestions(references);

            if (cyclicQuestions.isEmpty()) {
                log.info("No cycles detected - packet is clean!");
                return workingTossups;
            }

            log.warn("Found {} questions involved in cycles: {}", cyclicQuestions.size(), cyclicQuestions);

            // Pick a random question from the cycle to regenerate
            int questionToReplace = cyclicQuestions.get(new Random().nextInt(cyclicQuestions.size()));
            log.info("Randomly selected question {} to regenerate and break the cycle", questionToReplace);

            // Generate a new answer, avoiding all existing answers
            log.info("Generating replacement answer (avoiding {} existing answers)", workingAnswers.size());
            List<String> newAnswers = generateAnswerList(
                    topic,
                    additionalContext,
                    factSources,
                    1,
                    workingAnswers  // Avoid all existing answers
            );

            if (newAnswers.isEmpty()) {
                log.error("Failed to generate replacement answer on attempt {}", attempt);
                continue;
            }

            String newAnswer = newAnswers.get(0);
            log.info("Generated new answer: {}", newAnswer);

            // Craft new question
            Tossup newTossup = craftQuestionForAnswer(topic, newAnswer, factSources, additionalContext);
            log.info("Crafted new question for replacement answer");

            // Replace in working lists
            workingTossups.set(questionToReplace, newTossup);
            workingAnswers.set(questionToReplace, newAnswer);

            log.info("Replaced question {} with new non-cyclical question", questionToReplace);
        }

        // If we get here, we couldn't resolve cycles after max attempts
        log.error("Failed to resolve cycles after {} attempts - rejecting packet", maxAttempts);
        throw new RuntimeException("Unable to resolve cyclical cross-references after " + maxAttempts + " attempts. Please try again.");
    }

    /**
     * Find all questions involved in cyclical references
     */
    private List<Integer> findCyclicQuestions(List<QuestionReference> references) {
        Set<Integer> cyclicQuestions = new HashSet<>();

        for (QuestionReference ref : references) {
            for (Integer targetIndex : ref.referencesTo) {
                // Check if the target also references back to this one
                QuestionReference targetRef = references.get(targetIndex);
                if (targetRef.referencesTo.contains(ref.questionIndex)) {
                    cyclicQuestions.add(ref.questionIndex);
                    cyclicQuestions.add(targetIndex);
                    log.debug("Cycle detected between questions {} and {}", ref.questionIndex, targetIndex);
                }
            }
        }

        return new ArrayList<>(cyclicQuestions);
    }

    /**
     * Record for tracking question/answer cross-references
     */
    private record QuestionReference(
            int questionIndex,
            String answer,
            List<Integer> referencesTo  // Indices of other answers this question might reference
    ) {}

    /**
     * Analyze all questions for cross-references to other answers
     */
    private List<QuestionReference> analyzeQuestionCrossReferences(List<Tossup> tossups) {
        log.info("Analyzing questions for cross-references to other answers");

        List<QuestionReference> references = new ArrayList<>();

        for (int i = 0; i < tossups.size(); i++) {
            Tossup currentTossup = tossups.get(i);
            List<Integer> referencesTo = new ArrayList<>();

            // Check if this question mentions any other answer
            for (int j = 0; j < tossups.size(); j++) {
                if (i == j) continue; // Skip self

                Tossup otherTossup = tossups.get(j);
                // Extract the main answer text (before any brackets or parentheses)
                String otherAnswer = extractMainAnswer(otherTossup.getAnswer());

                // Check if current question contains the other answer
                if (currentTossup.getQuestion().toLowerCase().contains(otherAnswer.toLowerCase())) {
                    referencesTo.add(j);
                    log.warn("Question {} references answer {}: '{}' mentioned in question about '{}'",
                            i, j, otherAnswer, extractMainAnswer(currentTossup.getAnswer()));
                }
            }

            references.add(new QuestionReference(i, currentTossup.getAnswer(), referencesTo));
        }

        return references;
    }

    /**
     * Extract main answer from NAQT format (removes brackets and parentheses)
     */
    private String extractMainAnswer(String answerLine) {
        // Remove "ANSWER:" prefix
        String answer = answerLine.replaceFirst("(?i)ANSWER:\\s*", "");

        // Remove bracketed alternates [...]
        answer = answer.replaceAll("\\[.*?\\]", "");

        // Remove parenthetical clarifications (...)
        answer = answer.replaceAll("\\(.*?\\)", "");

        return answer.trim();
    }

    /**
     * Order questions intelligently to minimize giveaways using topological sort approach
     */
    private List<Tossup> orderQuestionsIntelligently(List<Tossup> tossups, List<QuestionReference> references) {
        log.info("Ordering questions to minimize cross-reference giveaways");

        // Build dependency graph: if question A references answer B, then B must come before A
        Map<Integer, List<Integer>> dependencies = new HashMap<>();
        Map<Integer, Integer> inDegree = new HashMap<>();

        for (int i = 0; i < tossups.size(); i++) {
            dependencies.put(i, new ArrayList<>());
            inDegree.put(i, 0);
        }

        for (QuestionReference ref : references) {
            for (Integer targetIndex : ref.referencesTo) {
                // Question ref.questionIndex depends on answer targetIndex
                // So targetIndex must come before ref.questionIndex
                dependencies.get(targetIndex).add(ref.questionIndex);
                inDegree.put(ref.questionIndex, inDegree.get(ref.questionIndex) + 1);
            }
        }

        // Topological sort using Kahn's algorithm
        Queue<Integer> queue = new LinkedList<>();
        for (int i = 0; i < tossups.size(); i++) {
            if (inDegree.get(i) == 0) {
                queue.offer(i);
            }
        }

        List<Integer> sortedIndices = new ArrayList<>();
        while (!queue.isEmpty()) {
            int current = queue.poll();
            sortedIndices.add(current);

            for (int dependent : dependencies.get(current)) {
                inDegree.put(dependent, inDegree.get(dependent) - 1);
                if (inDegree.get(dependent) == 0) {
                    queue.offer(dependent);
                }
            }
        }

        // Check if we got all questions (if not, there's a cycle)
        if (sortedIndices.size() != tossups.size()) {
            log.warn("Could not order all questions (possible undetected cycle). Using original order.");
            return tossups;
        }

        // Build reordered list
        List<Tossup> orderedTossups = new ArrayList<>();
        for (int index : sortedIndices) {
            orderedTossups.add(tossups.get(index));
        }

        log.info("Successfully reordered questions based on cross-references");
        return orderedTossups;
    }
}

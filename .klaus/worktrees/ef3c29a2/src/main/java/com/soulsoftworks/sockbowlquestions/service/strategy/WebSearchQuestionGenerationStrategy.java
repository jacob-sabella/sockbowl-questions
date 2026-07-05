package com.soulsoftworks.sockbowlquestions.service.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulsoftworks.sockbowlquestions.config.AiPrompts;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.service.ChatClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Knowledge-first question generation strategy.
 *
 * This strategy:
 * 1. Gathers comprehensive knowledge using the LLM
 * 2. Generates a list of potential answers based on gathered facts
 * 3. For each answer, crafts a pyramidal NAQT-style question
 * 4. Detects and resolves cross-references between questions
 * 5. Orders questions intelligently to minimize giveaways
 */
@Component("webSearchStrategy")
@Slf4j
public class WebSearchQuestionGenerationStrategy implements QuestionGenerationStrategy {

    private final ChatClientFactory chatClientFactory;
    private final PacketRepository packetRepository;
    private final ObjectMapper objectMapper;
    private final int candidateMultiplier;
    private final DefaultQuestionGenerationStrategy delegateStrategy;

    public WebSearchQuestionGenerationStrategy(
            ChatClientFactory chatClientFactory,
            PacketRepository packetRepository,
            AiPrompts aiPrompts,
            @Value("${sockbowl.ai.packetgen.candidate-multiplier:3}") int candidateMultiplier) {
        this.chatClientFactory = chatClientFactory;
        this.packetRepository = packetRepository;
        this.objectMapper = new ObjectMapper();
        this.candidateMultiplier = candidateMultiplier;
        // Create delegate strategy for bonus generation (for now, bonuses use default strategy)
        this.delegateStrategy = new DefaultQuestionGenerationStrategy(chatClientFactory, aiPrompts, packetRepository);
        log.info("Initialized WebSearchQuestionGenerationStrategy with candidate multiplier: {}", candidateMultiplier);
    }

    @Override
    public String getStrategyName() {
        return "knowledge-first";
    }

    @Override
    public Packet generatePacket(String topic, String additionalContext, int questionCount, boolean generateBonuses, AiRequestContext requestContext) throws JsonProcessingException {
        // Resolve ChatClient based on request context
        ChatClient chatClient = chatClientFactory.getChatClient(requestContext);

        log.info("=== Starting Packet Generation (Knowledge-First Strategy) ===");
        log.info("Topic: {}", topic);
        log.info("Additional Context: {}", additionalContext);
        log.info("Target number of tossups: {}", questionCount);
        log.info("Generate bonuses: {}", generateBonuses);

        // STEP 1: Gather comprehensive knowledge using local LLM
        log.info("STEP 1: Generating comprehensive knowledge about: {}", topic);
        String factSources = gatherKnowledge(chatClient, topic, additionalContext);
        log.info("Generated {} characters of knowledge content", factSources.length());

        // STEP 2: Iteratively generate and refine answers until they match criteria
        log.info("STEP 2: Iteratively generating {} high-quality answers matching context", questionCount);
        List<String> answers = iterativelyGenerateAnswers(chatClient, topic, additionalContext, factSources, questionCount);
        log.info("Final answer set ({} answers): {}", answers.size(), answers);

        // STEP 3: For each answer, craft a question
        log.info("STEP 3: Crafting questions for each answer");
        List<Tossup> tossups = new ArrayList<>();

        for (int i = 0; i < answers.size(); i++) {
            String answer = answers.get(i);
            log.info("Crafting question {} of {} for answer: {}", i + 1, answers.size(), answer);

            Tossup tossup = craftQuestionForAnswer(chatClient, topic, answer, factSources, additionalContext);
            tossups.add(tossup);
        }

        // STEP 4: Analyze for cross-references and resolve cycles
        log.info("STEP 4: Analyzing questions for cross-references and resolving any cycles");
        List<Tossup> finalTossups = resolveCrossReferencesAndCycles(chatClient, tossups, topic, additionalContext, factSources, answers);

        // STEP 5: Order questions intelligently to minimize giveaways
        log.info("STEP 5: Ordering questions to minimize cross-reference giveaways");
        List<QuestionReference> finalReferences = analyzeQuestionCrossReferences(chatClient, finalTossups);
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

        // STEP 8: Generate bonuses (if requested)
        List<com.soulsoftworks.sockbowlquestions.models.nodes.Bonus> existingBonuses = new ArrayList<>();
        List<com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus> bonusList = new ArrayList<>();

        if (generateBonuses) {
            log.info("STEP 8: Generating {} bonuses", questionCount);
            for (int i = 0; i < questionCount; i++) {
                log.info("Generating bonus {} of {}", i + 1, questionCount);
                com.soulsoftworks.sockbowlquestions.models.nodes.Bonus bonus = generateBonus(
                    topic,
                    additionalContext,
                    existingBonuses,
                    orderedTossups,
                    requestContext
                );

                existingBonuses.add(bonus);

                com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus containsBonus =
                    new com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus(i + 1, bonus);
                bonusList.add(containsBonus);
            }
        } else {
            log.info("STEP 8: Skipping bonus generation as requested");
        }

        Packet packet = packetBuilder
                .bonuses(bonusList)
                .build();
        packetRepository.save(packet);

        log.info("=== Packet Generation Complete ===");
        if (generateBonuses) {
            log.info("Generated {} tossups and {} bonuses", orderedTossups.size(), existingBonuses.size());
        } else {
            log.info("Generated {} tossups (bonuses skipped)", orderedTossups.size());
        }
        return packet;
    }

    @Override
    public Tossup generateTossup(String topic, String additionalContext, List<Tossup> existingTossups, AiRequestContext requestContext) {
        // Resolve ChatClient based on request context
        ChatClient chatClient = chatClientFactory.getChatClient(requestContext);

        log.info("=== Generating Single Tossup (Knowledge-First Strategy) ===");
        log.info("Topic: {}", topic);

        // Gather knowledge
        String factSources = gatherKnowledge(chatClient, topic, additionalContext);

        // Generate a single answer
        List<String> existingAnswers = existingTossups.stream()
                .map(Tossup::getAnswer)
                .collect(Collectors.toList());

        List<String> answers = generateAnswerList(chatClient, topic, additionalContext, factSources, 1, existingAnswers);

        if (answers.isEmpty()) {
            throw new RuntimeException("Failed to generate answer for topic: " + topic);
        }

        String answer = answers.get(0);
        log.info("Generated answer: {}", answer);

        // Craft question for this answer
        return craftQuestionForAnswer(chatClient, topic, answer, factSources, additionalContext);
    }

    @Override
    public Bonus generateBonus(String topic, String additionalContext, List<Bonus> existingBonuses, List<Tossup> existingTossups, AiRequestContext requestContext) {
        // Resolve ChatClient based on request context
        ChatClient chatClient = chatClientFactory.getChatClient(requestContext);

        log.info("=== Generating Single Bonus (Knowledge-First Strategy) ===");
        log.info("Topic: {}", topic);
        log.info("Additional Context: {}", additionalContext);

        // STEP 1: Gather knowledge about the topic
        log.info("STEP 1: Gathering knowledge for bonus generation");
        String factSources = gatherKnowledge(chatClient, topic, additionalContext);

        // STEP 2: Generate a themed triplet of answers
        log.info("STEP 2: Generating themed answer triplet");
        BonusAnswerTriplet answerTriplet = generateBonusAnswerTriplet(
                chatClient,
                topic,
                additionalContext,
                factSources,
                existingBonuses,
                existingTossups
        );

        log.info("Generated bonus theme: {}", answerTriplet.theme);
        log.info("Answer A: {}", answerTriplet.answerA);
        log.info("Answer B: {}", answerTriplet.answerB);
        log.info("Answer C: {}", answerTriplet.answerC);

        // STEP 3: Generate preamble based on the theme and answers
        log.info("STEP 3: Generating preamble from theme");
        String preamble = generateBonusPreamble(chatClient, answerTriplet);

        // STEP 4: Generate individual questions for each answer
        log.info("STEP 4: Generating questions for each answer");
        String questionA = generateBonusPartQuestion(chatClient, answerTriplet.theme, answerTriplet.answerA, preamble, "A");
        String questionB = generateBonusPartQuestion(chatClient, answerTriplet.theme, answerTriplet.answerB, preamble, "B");
        String questionC = generateBonusPartQuestion(chatClient, answerTriplet.theme, answerTriplet.answerC, preamble, "C");

        // Create bonus parts
        BonusPart partA = new BonusPart();
        partA.setQuestion(questionA);
        partA.setAnswer(answerTriplet.answerA);

        BonusPart partB = new BonusPart();
        partB.setQuestion(questionB);
        partB.setAnswer(answerTriplet.answerB);

        BonusPart partC = new BonusPart();
        partC.setQuestion(questionC);
        partC.setAnswer(answerTriplet.answerC);

        // Create bonus with parts
        Bonus bonus = new Bonus();
        bonus.setPreamble(preamble);
        bonus.setBonusParts(Arrays.asList(
                new com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart(1, partA),
                new com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart(2, partB),
                new com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart(3, partC)
        ));

        log.info("=== Bonus Generation Complete ===");
        return bonus;
    }

    /**
     * Record to hold a bonus answer triplet with theme
     */
    private record BonusAnswerTriplet(
            String theme,
            String answerA,
            String answerB,
            String answerC
    ) {}

    /**
     * Generate a themed triplet of bonus answers
     */
    private BonusAnswerTriplet generateBonusAnswerTriplet(
            ChatClient chatClient,
            String topic,
            String additionalContext,
            String factSources,
            List<Bonus> existingBonuses,
            List<Tossup> existingTossups) {

        log.info("Generating themed bonus answer triplet");

        // Build list of existing answers to avoid
        List<String> existingAnswers = new ArrayList<>();

        // Add existing bonus answers
        for (Bonus bonus : existingBonuses) {
            if (bonus.getBonusParts() != null) {
                for (var part : bonus.getBonusParts()) {
                    if (part.getBonusPart() != null && part.getBonusPart().getAnswer() != null) {
                        existingAnswers.add(part.getBonusPart().getAnswer());
                    }
                }
            }
        }

        // Add existing tossup answers
        for (Tossup tossup : existingTossups) {
            if (tossup.getAnswer() != null) {
                existingAnswers.add(tossup.getAnswer());
            }
        }

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Based on the following knowledge about '").append(topic).append("', generate a themed quiz bowl bonus.\n\n");

        if (additionalContext != null && !additionalContext.isEmpty()) {
            promptBuilder.append("Additional context: ").append(additionalContext).append("\n\n");
        }

        promptBuilder.append("KNOWLEDGE:\n").append(factSources).append("\n\n");

        promptBuilder.append("Generate a themed triplet of THREE related answers for an ADVANCED quiz bowl bonus.\n\n");

        promptBuilder.append("REQUIREMENTS:\n");
        promptBuilder.append("1. All three answers must be related by a SPECIFIC, INTERESTING theme\n");
        promptBuilder.append("2. The theme should be QUIZ BOWL-WORTHY (not too obvious, requires knowledge)\n");
        promptBuilder.append("3. Each answer must be a SPECIFIC, referenceable entity (proper noun, defined term, specific work, etc.)\n");
        promptBuilder.append("4. Answers should be formatted in NAQT answer line format\n");
        promptBuilder.append("5. AVOID obvious/canonical examples - choose lesser-known but notable answers\n");
        promptBuilder.append("6. The three answers should have similar difficulty levels\n");
        promptBuilder.append("7. The theme should be specific enough to be interesting but broad enough to support 3 answers\n\n");

        promptBuilder.append("EXAMPLE THEMES (for reference, create your own):\n");
        promptBuilder.append("- 'Works that prominently feature mirrors or reflections'\n");
        promptBuilder.append("- 'Scientific discoveries made by accident'\n");
        promptBuilder.append("- 'Historical figures who died in duels'\n");
        promptBuilder.append("- 'Artworks depicting the Annunciation'\n");
        promptBuilder.append("- 'Poems written in terza rima'\n\n");

        if (!existingAnswers.isEmpty()) {
            promptBuilder.append("AVOID these answers (already used):\n");
            for (String ans : existingAnswers) {
                promptBuilder.append("- ").append(ans).append("\n");
            }
            promptBuilder.append("\n");
        }

        promptBuilder.append("Return as JSON with fields: theme, answer_a, answer_b, answer_c\n");
        promptBuilder.append("The theme should be a clear, specific description of what connects the three answers.\n");

        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Attempt {} to generate bonus answer triplet", attempt);

                String response = chatClient.prompt()
                        .user(promptBuilder.toString())
                        .call()
                        .content();

                log.info("Raw triplet response: {}", response);

                String json = extractJson(response);
                Map<String, String> tripletData = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});

                String theme = tripletData.get("theme");
                String answerA = tripletData.get("answer_a");
                String answerB = tripletData.get("answer_b");
                String answerC = tripletData.get("answer_c");

                if (theme != null && answerA != null && answerB != null && answerC != null) {
                    log.info("Successfully generated bonus answer triplet");
                    return new BonusAnswerTriplet(theme, answerA, answerB, answerC);
                }

                throw new IllegalArgumentException("Missing required fields in triplet response");

            } catch (Exception e) {
                log.warn("Attempt {} failed: {}", attempt, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("Failed to generate bonus answer triplet after {} attempts", maxRetries);
                    throw new RuntimeException("Failed to generate bonus answer triplet: " + e.getMessage(), e);
                }
            }
        }

        throw new RuntimeException("Failed to generate bonus answer triplet");
    }

    /**
     * Generate preamble from themed answers
     */
    private String generateBonusPreamble(ChatClient chatClient, BonusAnswerTriplet triplet) {
        log.info("Generating preamble for theme: {}", triplet.theme);

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Generate a concise, engaging preamble for a quiz bowl bonus.\n\n");
        promptBuilder.append("The bonus theme is: ").append(triplet.theme).append("\n\n");
        promptBuilder.append("The three answers are:\n");
        promptBuilder.append("- ").append(triplet.answerA).append("\n");
        promptBuilder.append("- ").append(triplet.answerB).append("\n");
        promptBuilder.append("- ").append(triplet.answerC).append("\n\n");

        promptBuilder.append("PREAMBLE REQUIREMENTS:\n");
        promptBuilder.append("1. Should be 1-2 sentences (roughly 20-40 words)\n");
        promptBuilder.append("2. Clearly state the theme/connection\n");
        promptBuilder.append("3. Set up what the three parts will ask about\n");
        promptBuilder.append("4. Should end with a phrase like 'For 10 points each:' or 'For ten points each, answer the following:'\n");
        promptBuilder.append("5. Be engaging and precise\n\n");

        promptBuilder.append("EXAMPLE PREAMBLES:\n");
        promptBuilder.append("- 'This bonus is about works of art that depict the Annunciation. For 10 points each:'\n");
        promptBuilder.append("- 'Answer these questions about scientific discoveries made by accident. For ten points each:'\n");
        promptBuilder.append("- 'Identify these historical figures who met their end in duels, for 10 points each:'\n\n");

        promptBuilder.append("Return ONLY the preamble text as a JSON object with a single field 'preamble'.\n");

        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Attempt {} to generate preamble", attempt);

                String response = chatClient.prompt()
                        .user(promptBuilder.toString())
                        .call()
                        .content();

                log.info("Raw preamble response: {}", response);

                String json = extractJson(response);
                Map<String, String> preambleData = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});

                String preamble = preambleData.get("preamble");
                if (preamble != null && !preamble.isEmpty()) {
                    log.info("Successfully generated preamble: {}", preamble);
                    return preamble;
                }

                throw new IllegalArgumentException("Missing preamble in response");

            } catch (Exception e) {
                log.warn("Attempt {} failed: {}", attempt, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("Failed to generate preamble after {} attempts", maxRetries);
                    throw new RuntimeException("Failed to generate preamble: " + e.getMessage(), e);
                }
            }
        }

        throw new RuntimeException("Failed to generate preamble");
    }

    /**
     * Generate a single bonus part question for a given answer
     */
    private String generateBonusPartQuestion(
            ChatClient chatClient,
            String theme,
            String answer,
            String preamble,
            String partLabel) {

        log.info("Generating question for part {} with answer: {}", partLabel, answer);

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Generate a quiz bowl bonus part question for this answer.\n\n");
        promptBuilder.append("Bonus theme: ").append(theme).append("\n");
        promptBuilder.append("Preamble: ").append(preamble).append("\n\n");
        promptBuilder.append("Part ").append(partLabel).append(" Answer: ").append(answer).append("\n\n");

        promptBuilder.append("QUESTION REQUIREMENTS:\n");
        promptBuilder.append("1. Should be 1-3 sentences (roughly 30-80 words)\n");
        promptBuilder.append("2. Provide enough clues to identify the answer\n");
        promptBuilder.append("3. Include SPECIFIC, VERIFIABLE facts\n");
        promptBuilder.append("4. Progress from harder to easier clues within the question\n");
        promptBuilder.append("5. Should be self-contained but relate to the theme\n");
        promptBuilder.append("6. Don't start with 'Part A:', 'Part B:', etc. - just the question text\n\n");

        promptBuilder.append("Return ONLY the question text as a JSON object with a single field 'question'.\n");

        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Attempt {} to generate part {} question", attempt, partLabel);

                String response = chatClient.prompt()
                        .user(promptBuilder.toString())
                        .call()
                        .content();

                log.info("Raw question response: {}", response);

                String json = extractJson(response);
                Map<String, String> questionData = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});

                String question = questionData.get("question");
                if (question != null && !question.isEmpty()) {
                    log.info("Successfully generated question for part {}", partLabel);
                    return question;
                }

                throw new IllegalArgumentException("Missing question in response");

            } catch (Exception e) {
                log.warn("Attempt {} failed: {}", attempt, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("Failed to generate part {} question after {} attempts", partLabel, maxRetries);
                    throw new RuntimeException("Failed to generate part question: " + e.getMessage(), e);
                }
            }
        }

        throw new RuntimeException("Failed to generate part question");
    }

    /**
     * STEP 1: Gather comprehensive knowledge using the LLM.
     */
    private String gatherKnowledge(ChatClient chatClient, String topic, String additionalContext) {
        log.info("Gathering comprehensive knowledge for topic: {}", topic);

        StringBuilder knowledge = new StringBuilder();

        try {
            // Build comprehensive knowledge prompt
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("Provide comprehensive, factual, and detailed information about: ").append(topic);

            if (additionalContext != null && !additionalContext.isEmpty()) {
                promptBuilder.append("\n\nAdditional context/requirements: ").append(additionalContext);
            }

            promptBuilder.append("\n\nYour response should include:");
            promptBuilder.append("\n- Key facts, dates, names, and specific details");
            promptBuilder.append("\n- Historical context and significance");
            promptBuilder.append("\n- Notable examples and instances (avoid only the most famous/canonical ones)");
            promptBuilder.append("\n- Technical or specialized information");
            promptBuilder.append("\n- Diverse aspects covering different categories and time periods");
            promptBuilder.append("\n- Lesser-known but notable and significant details");
            promptBuilder.append("\n\nFocus on providing rich, specific, verifiable information that can support ADVANCED quiz bowl questions.");

            String llmKnowledge = chatClient.prompt()
                    .user(promptBuilder.toString())
                    .call()
                    .content();

            knowledge.append("=== Comprehensive Knowledge ===\n").append(llmKnowledge).append("\n\n");

            // Gather additional perspectives if context is provided
            if (additionalContext != null && !additionalContext.isEmpty()) {
                log.info("Gathering additional context-specific knowledge");

                String contextPrompt = String.format(
                    "Focusing specifically on '%s' in the context of '%s', provide additional details, examples, and information that would be valuable for creating advanced quiz bowl questions. Include lesser-known but notable aspects.",
                    topic, additionalContext
                );

                String contextKnowledge = chatClient.prompt()
                        .user(contextPrompt)
                        .call()
                        .content();

                knowledge.append("=== Context-Specific Knowledge ===\n").append(contextKnowledge).append("\n\n");
            }

        } catch (Exception e) {
            log.error("Error gathering knowledge: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to gather knowledge for topic: " + topic, e);
        }

        log.info("Successfully gathered {} characters of knowledge", knowledge.length());
        return knowledge.toString();
    }


    /**
     * Iteratively generate and refine answers until they meet quality criteria.
     * This is a recursive process that:
     * 1. Generates a large pool of candidates (3x needed)
     * 2. Evaluates each against the additional context
     * 3. If not enough good matches, searches deeper and repeats
     */
    private List<String> iterativelyGenerateAnswers(
            ChatClient chatClient,
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
                    chatClient,
                    topic,
                    additionalContext,
                    cumulativeFacts.toString(),
                    candidateCount,
                    finalAnswers // Avoid duplicates
            );

            log.info("Generated {} candidates", candidates.size());

            // Evaluate and score candidates against context
            List<EvaluatedAnswer> evaluatedCandidates = evaluateAnswers(
                    chatClient,
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
                String deeperSearch = performDeeperSearch(chatClient, topic, additionalContext, finalAnswers);
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
            ChatClient chatClient,
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

            // Try to parse with JSON remediation retry logic
            List<Map<String, Object>> evaluations = parseEvaluationJsonWithRetry(
                    chatClient,
                    response,
                    candidates.size()
            );

            if (evaluations == null) {
                // All remediation attempts failed
                log.error("All JSON remediation attempts failed, returning all candidates with neutral scores");
                return candidates.stream()
                        .map(ans -> new EvaluatedAnswer(ans, 5.0, "JSON parsing failed after retries"))
                        .collect(Collectors.toList());
            }

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
     * Parse evaluation JSON with retry and remediation logic.
     * Tries up to 5 times to fix malformed JSON before giving up.
     *
     * @param chatClient ChatClient for asking LLM to fix JSON
     * @param rawResponse Raw response from LLM
     * @param expectedCount Expected number of items in the array
     * @return Parsed list of evaluations, or null if all attempts failed
     */
    private List<Map<String, Object>> parseEvaluationJsonWithRetry(
            ChatClient chatClient,
            String rawResponse,
            int expectedCount) {

        final int maxAttempts = 5;
        String currentJson = rawResponse;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("JSON parse attempt {} of {}", attempt, maxAttempts);

                // Extract JSON array from response
                String json = extractJsonArray(currentJson);

                // Try to parse
                List<Map<String, Object>> evaluations = objectMapper.readValue(
                        json,
                        new TypeReference<List<Map<String, Object>>>() {}
                );

                // Validate we got all expected items
                if (evaluations.size() < expectedCount) {
                    log.warn("Parsed {} items but expected {}. Trying remediation.",
                            evaluations.size(), expectedCount);
                    throw new IllegalArgumentException(
                            "Incomplete JSON: got " + evaluations.size() + " items, expected " + expectedCount
                    );
                }

                log.info("Successfully parsed JSON on attempt {}", attempt);
                return evaluations;

            } catch (Exception e) {
                log.warn("Parse attempt {} failed: {}", attempt, e.getMessage());

                if (attempt == maxAttempts) {
                    log.error("All {} JSON remediation attempts failed", maxAttempts);
                    return null;
                }

                // Ask LLM to fix the JSON
                log.info("Requesting JSON remediation from LLM (attempt {})", attempt + 1);
                currentJson = requestJsonRemediation(chatClient, currentJson, e.getMessage(), expectedCount);

                if (currentJson == null) {
                    log.error("LLM failed to provide remediated JSON");
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * Ask LLM to fix malformed JSON
     *
     * @param chatClient ChatClient for making requests
     * @param malformedJson The malformed JSON string
     * @param errorMessage The parsing error message
     * @param expectedCount Expected number of array items
     * @return Fixed JSON string, or null if remediation failed
     */
    private String requestJsonRemediation(
            ChatClient chatClient,
            String malformedJson,
            String errorMessage,
            int expectedCount) {

        try {
            StringBuilder remediationPrompt = new StringBuilder();
            remediationPrompt.append("The following JSON has a syntax error and failed to parse.\n\n");
            remediationPrompt.append("ERROR: ").append(errorMessage).append("\n\n");
            remediationPrompt.append("MALFORMED JSON:\n");
            remediationPrompt.append(malformedJson).append("\n\n");
            remediationPrompt.append("REQUIREMENTS:\n");
            remediationPrompt.append("1. Fix ALL JSON syntax errors\n");
            remediationPrompt.append("2. Ensure it's a valid JSON array with exactly ").append(expectedCount).append(" objects\n");
            remediationPrompt.append("3. Each object must have: index (number), score (number), reasoning (string)\n");
            remediationPrompt.append("4. Remove trailing commas\n");
            remediationPrompt.append("5. Ensure all strings are properly quoted\n");
            remediationPrompt.append("6. Ensure all property names are in double quotes\n");
            remediationPrompt.append("7. Do NOT change the content/meaning, only fix syntax\n\n");
            remediationPrompt.append("Return ONLY the fixed JSON array, nothing else.\n");

            String response = chatClient.prompt()
                    .user(remediationPrompt.toString())
                    .call()
                    .content();

            log.info("Received remediated JSON (length: {})", response.length());
            return response;

        } catch (Exception e) {
            log.error("Failed to get JSON remediation from LLM: {}", e.getMessage());
            return null;
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
     * Gather additional knowledge when initial candidates aren't suitable
     */
    private String performDeeperSearch(ChatClient chatClient, String topic, String additionalContext, List<String> existingAnswers) {
        log.info("Gathering additional knowledge to find more suitable answers");

        try {
            // Build a prompt to get more detailed, focused knowledge
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("We need additional ADVANCED, NON-OBVIOUS information about: ").append(topic);

            if (additionalContext != null && !additionalContext.isEmpty()) {
                promptBuilder.append("\n\nContext/Requirements: ").append(additionalContext);
            }

            promptBuilder.append("\n\nWe already have information about:\n");
            if (existingAnswers.isEmpty()) {
                promptBuilder.append("(This is our first attempt)\n");
            } else {
                for (String ans : existingAnswers) {
                    promptBuilder.append("- ").append(ans).append("\n");
                }
            }

            promptBuilder.append("\nProvide DIFFERENT, MORE SPECIFIC information focusing on:");
            promptBuilder.append("\n- LESSER-KNOWN but notable and quiz bowl-worthy aspects");
            promptBuilder.append("\n- Specialized, technical, or niche details");
            promptBuilder.append("\n- Second and third-order concepts (not the main/obvious ones)");
            promptBuilder.append("\n- Specific examples, works, events, or figures that are NOT the most famous");
            promptBuilder.append("\n\n⚠️ CRITICAL: Avoid obvious, canonical, or textbook examples. Focus on depth and specificity.");

            String deeperKnowledge = chatClient.prompt()
                    .user(promptBuilder.toString())
                    .call()
                    .content();

            log.info("Gathered {} chars of additional knowledge", deeperKnowledge.length());
            return deeperKnowledge;

        } catch (Exception e) {
            log.error("Failed to gather additional knowledge: {}", e.getMessage());
            return "";
        }
    }

    /**
     * STEP 2: Generate a list of potential answers based on gathered facts.
     */
    private List<String> generateAnswerList(ChatClient chatClient, String topic, String additionalContext, String factSources, int count) {
        return generateAnswerList(chatClient, topic, additionalContext, factSources, count, Collections.emptyList());
    }

    /**
     * STEP 2: Generate a list of potential answers based on gathered facts.
     * Avoids answers in the exclusion list.
     */
    private List<String> generateAnswerList(
            ChatClient chatClient,
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
    private Tossup craftQuestionForAnswer(ChatClient chatClient, String topic, String answer, String factSources, String additionalContext) {
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
     * Resolve cross-references and cycles by regenerating problematic questions
     */
    private List<Tossup> resolveCrossReferencesAndCycles(
            ChatClient chatClient,
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
            List<QuestionReference> references = analyzeQuestionCrossReferences(chatClient, workingTossups);

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
                    chatClient,
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
            Tossup newTossup = craftQuestionForAnswer(chatClient, topic, newAnswer, factSources, additionalContext);
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
    private List<QuestionReference> analyzeQuestionCrossReferences(ChatClient chatClient, List<Tossup> tossups) {
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

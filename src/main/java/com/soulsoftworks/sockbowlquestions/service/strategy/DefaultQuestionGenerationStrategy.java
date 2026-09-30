package com.soulsoftworks.sockbowlquestions.service.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.soulsoftworks.sockbowlquestions.config.AiPrompts;
import com.soulsoftworks.sockbowlquestions.dto.AiProvider;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.service.ChatClientFactory;
import com.soulsoftworks.sockbowlquestions.service.LlmResponses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Default question generation strategy - the original approach.
 * Generates question and answer together using a single LLM call per tossup.
 */
@Component("defaultStrategy")
@Slf4j
public class DefaultQuestionGenerationStrategy implements QuestionGenerationStrategy {

    private final ChatClientFactory chatClientFactory;
    private final AiPrompts aiPrompts;

    /**
     * The server's own default chat model name (D13, M4-PV-01), used to stamp
     * {@code aiModel} when the caller didn't BYO a model via {@link AiRequestContext}.
     * Falls back to whichever provider's {@code chat.options.model} is configured.
     */
    @Value("${spring.ai.openai.chat.options.model:${spring.ai.ollama.chat.options.model:unknown}}")
    private String defaultChatModel;

    public DefaultQuestionGenerationStrategy(
            ChatClientFactory chatClientFactory,
            AiPrompts aiPrompts) {
        this.chatClientFactory = chatClientFactory;
        this.aiPrompts = aiPrompts;
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Anthropic structured-output schemas (object, all fields required, no extras). */
    private static final String TOSSUP_SCHEMA = stringObjectSchema("question", "answer");
    private static final String BONUS_SCHEMA = stringObjectSchema("preamble", "part_a_question", "part_a_answer",
            "part_b_question", "part_b_answer", "part_c_question", "part_c_answer");

    private static String stringObjectSchema(String... fields) {
        StringBuilder properties = new StringBuilder();
        StringBuilder required = new StringBuilder();
        for (String field : fields) {
            if (!properties.isEmpty()) {
                properties.append(',');
                required.append(',');
            }
            properties.append('"').append(field).append("\":{\"type\":\"string\"}");
            required.append('"').append(field).append('"');
        }
        return "{\"type\":\"object\",\"properties\":{" + properties + "},\"required\":[" + required
                + "],\"additionalProperties\":false}";
    }

    /**
     * The prompt section that pins the question to the packet's difficulty: its name
     * and the owner-edited description from the taxonomy. It outranks the templates'
     * push toward obscure answers, which suits hard packets and not easy ones. No
     * difficulty, no section.
     */
    static String difficultyGuidance(Difficulty difficulty) {
        if (difficulty == null || difficulty.getName() == null || difficulty.getName().isBlank()) {
            return "";
        }
        StringBuilder section = new StringBuilder("\n**TARGET DIFFICULTY (MANDATORY)**: ")
                .append(difficulty.getName().strip()).append('\n');
        String description = difficulty.getDescription();
        if (description != null && !description.isBlank()) {
            section.append('\n').append(description.strip()).append('\n');
        }
        section.append("\nWrite for players at exactly this level: the choice of answer, every clue and the ")
                .append("giveaway must fit it. This outranks any instruction below to prefer obscure, ")
                .append("specialist or non-canonical material. At easier levels, choose answers these players ")
                .append("can reasonably know and keep even the opening clues within their reach; at harder ")
                .append("levels, the lead-in clues should reward deep knowledge.\n");
        return section.toString();
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
    public Packet generatePacket(String topic, String additionalContext, Difficulty difficulty, int questionCount, boolean generateBonuses,
                                  AiRequestContext requestContext, String ownerId, String ownerDisplayName) throws JsonProcessingException {
        log.info("=== Starting New Packet Generation (Default Strategy) ===");
        log.info("Topic: {}", topic);
        log.info("Additional Context: {}", additionalContext);
        log.info("Difficulty: {}", difficulty != null ? difficulty.getName() : "(none)");
        log.info("Target number of tossups: {}", questionCount);
        log.info("Generate bonuses: {}", generateBonuses);
        if (generateBonuses) {
            log.info("Will generate {} bonuses", questionCount);
        }

        Packet.PacketBuilder packetBuilder = Packet
                .builder()
                .name("Generated Packet: %s - %s".formatted(topic, UUID.randomUUID()))
                .ownerId(ownerId)
                .ownerDisplayName(ownerDisplayName)
                .difficulty(difficulty);

        List<Tossup> existingTossups = new ArrayList<>();
        List<Bonus> existingBonuses = new ArrayList<>();

        // Generate tossups
        for (int i = 0; i < questionCount; i++) {
            log.info("=== Generating Tossup {} of {} ===", i + 1, questionCount);
            log.info("Current topic: {}", topic);
            log.info("Number of existing tossups to avoid: {}", existingTossups.size());

            // One call per tossup: the prompt carries every earlier answer to avoid.
            Tossup tossup = generateTossup(topic, additionalContext, difficulty, existingTossups, requestContext);

            existingTossups.add(tossup);

            ContainsTossup containsTossup = ContainsTossup.builder()
                    .order(i + 1)
                    .tossup(tossup)
                    .build();

            packetBuilder.tossup(containsTossup);
        }

        // Generate bonuses (if requested)
        List<ContainsBonus> bonusList = new ArrayList<>();
        if (generateBonuses) {
            for (int i = 0; i < questionCount; i++) {
                log.info("=== Generating Bonus {} of {} ===", i + 1, questionCount);
                log.info("Current topic: {}", topic);
                log.info("Number of existing bonuses to avoid: {}", existingBonuses.size());

                Bonus bonus = generateBonus(topic, additionalContext, difficulty, existingBonuses, existingTossups, requestContext);

                existingBonuses.add(bonus);

                ContainsBonus containsBonus = new ContainsBonus(i + 1, bonus);
                bonusList.add(containsBonus);
            }
        } else {
            log.info("Skipping bonus generation as requested");
        }

        Packet packet = packetBuilder
                .bonuses(bonusList)
                // D2: a freshly generated packet is a draft until its owner publishes it.
                .visibility(PacketVisibility.defaultForNewPackets())
                // D13, M4-PV-01: AI-generated content records which model made it.
                .source(ContentSource.AI_GENERATED)
                .aiModel(resolveModel(requestContext))
                .build();
        // FIX3-Q: NOT saved here. The caller (QuestionGenerationService) persists
        // it under the packets-owned lock, so that lock is held only around the
        // fast save, never across this method's (possibly long) AI calls above.

        log.info("=== Packet Generation Complete ===");
        if (generateBonuses) {
            log.info("Generated {} tossups and {} bonuses", existingTossups.size(), existingBonuses.size());
        } else {
            log.info("Generated {} tossups (bonuses skipped)", existingTossups.size());
        }

        return packet;
    }

    @Override
    public Tossup generateTossup(String topic, String additionalContext, Difficulty difficulty, List<Tossup> existingTossups, AiRequestContext requestContext) {
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
        promptParams.put("tossup_topic", topic);
        promptParams.put("user_context", additionalContext != null ? additionalContext : "");
        promptParams.put("diversity_mandate", diversityMandate);
        promptParams.put("difficulty_guidance", difficultyGuidance(difficulty));
        promptParams.put("avoid_answers", avoidList(existingTossups.stream().map(Tossup::getAnswer).toList()));

        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(aiPrompts.getNaqtWriterPacketGenerationPrompt());

        String prompt = systemPromptTemplate.render(promptParams);
        log.debug("Tossup prompt: {}", prompt);

        TossupPromptDTO response = callForJson(requestContext, prompt, TOSSUP_SCHEMA, TossupPromptDTO.class,
                t -> t.question() != null && !t.question().isBlank() && t.answer() != null && !t.answer().isBlank());

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
                // D13, M4-PV-01: AI-generated content records which model made it.
                .source(ContentSource.AI_GENERATED)
                .aiModel(resolveModel(requestContext))
                .build();
    }

    @Override
    public Bonus generateBonus(String topic, String additionalContext, Difficulty difficulty, List<Bonus> existingBonuses, List<Tossup> existingTossups, AiRequestContext requestContext) {
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

        // Every answer already in the packet (all tossups, then earlier bonus parts).
        List<String> usedAnswers = new ArrayList<>(existingTossups.stream().map(Tossup::getAnswer).toList());
        for (Bonus earlier : existingBonuses) {
            if (earlier.getBonusParts() != null) {
                earlier.getBonusParts().forEach(part -> usedAnswers.add(part.getBonusPart().getAnswer()));
            }
        }
        String tossupContext = usedAnswers.isEmpty() ? ""
                : "\n\n**Answers already used in this packet** (do not reuse any of them as a bonus answer; related themes are fine):\n"
                        + avoidList(usedAnswers);

        Map<String, Object> promptParams = new HashMap<>();
        promptParams.put("bonus_number", existingBonuses.size() + 1);
        promptParams.put("bonus_topic", topic);
        promptParams.put("user_context", additionalContext != null ? additionalContext : "");
        promptParams.put("diversity_mandate", diversityMandate);
        promptParams.put("tossup_context", tossupContext);
        promptParams.put("difficulty_guidance", difficultyGuidance(difficulty));

        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(aiPrompts.getNaqtWriterBonusGenerationPrompt());

        String prompt = systemPromptTemplate.render(promptParams);
        log.debug("Bonus prompt: {}", prompt);

        BonusPromptDTO response = callForJson(requestContext, prompt, BONUS_SCHEMA, BonusPromptDTO.class,
                b -> Stream.of(b.preamble(), b.part_a_question(), b.part_a_answer(), b.part_b_question(),
                        b.part_b_answer(), b.part_c_question(), b.part_c_answer())
                        .allMatch(v -> v != null && !v.isBlank()));

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

        // D13, M4-PV-01: AI-generated content records which model made it.
        String resolvedModel = resolveModel(requestContext);

        // Create bonus parts
        BonusPart partA = new BonusPart();
        partA.setQuestion(response.part_a_question());
        partA.setAnswer(response.part_a_answer());
        partA.setSource(ContentSource.AI_GENERATED);
        partA.setAiModel(resolvedModel);

        BonusPart partB = new BonusPart();
        partB.setQuestion(response.part_b_question());
        partB.setAnswer(response.part_b_answer());
        partB.setSource(ContentSource.AI_GENERATED);
        partB.setAiModel(resolvedModel);

        BonusPart partC = new BonusPart();
        partC.setQuestion(response.part_c_question());
        partC.setAnswer(response.part_c_answer());
        partC.setSource(ContentSource.AI_GENERATED);
        partC.setAiModel(resolvedModel);

        // Create bonus with parts
        Bonus bonus = new Bonus();
        bonus.setPreamble(response.preamble());
        bonus.setBonusParts(Arrays.asList(
                new HasBonusPart(1, partA),
                new HasBonusPart(2, partB),
                new HasBonusPart(3, partC)
        ));
        bonus.setSource(ContentSource.AI_GENERATED);
        bonus.setAiModel(resolvedModel);

        return bonus;
    }

    /**
     * The model name to stamp as {@code aiModel} (D13, M4-PV-01): the caller's BYO
     * model when a custom API key/model was supplied, else the server's default.
     */
    private String resolveModel(AiRequestContext requestContext) {
        if (requestContext != null && requestContext.hasCustomConfig()
                && requestContext.getModel() != null && !requestContext.getModel().isBlank()) {
            return requestContext.getModel();
        }
        return defaultChatModel;
    }

    /** A bulleted list of answers for the prompt, or "(none yet)". */
    private static String avoidList(List<String> answers) {
        List<String> present = answers.stream().filter(a -> a != null && !a.isBlank()).toList();
        if (present.isEmpty()) {
            return "(none yet)";
        }
        StringBuilder list = new StringBuilder();
        present.forEach(a -> list.append("- ").append(a).append('\n'));
        return list.toString();
    }

    /**
     * One model call for a JSON answer of {@code type}. Only an unusable answer
     * (malformed or incomplete JSON) is retried, at most {@link #MAX_ATTEMPTS} times in
     * all; provider errors (bad key, rate limit, outage) are not retried here, since the
     * SDK already retries transient ones. On a saved Claude key the answer is
     * schema-constrained; a model that rejects structured outputs (400) gets one retry
     * without the schema.
     */
    private <T> T callForJson(AiRequestContext requestContext, String prompt, String schema, Class<T> type,
                              Predicate<T> complete) {
        boolean anthropic = requestContext != null && requestContext.getProvider() == AiProvider.ANTHROPIC
                && requestContext.hasCustomConfig();
        String activeSchema = anthropic ? schema : null;
        ChatClient chatClient = chatClientFactory.getChatClient(requestContext, activeSchema);
        String lastProblem = "no answer";
        int attempt = 0;
        while (attempt < MAX_ATTEMPTS) {
            attempt++;
            String raw;
            try {
                raw = LlmResponses.answerText(chatClient.prompt().user(prompt).call().chatResponse());
            } catch (RuntimeException e) {
                if (activeSchema != null && isAnthropicBadRequest(e)) {
                    log.warn("Model {} rejected the structured-output request ({}); retrying without a schema",
                            requestContext.getModel(), e.getMessage());
                    activeSchema = null;
                    chatClient = chatClientFactory.getChatClient(requestContext, null);
                    attempt--;
                    continue;
                }
                throw e;
            }
            log.debug("Raw AI response (attempt {}): {}", attempt, raw);
            try {
                T parsed = JSON.readValue(sanitizeJsonNewlines(extractJson(raw)), type);
                if (parsed != null && complete.test(parsed)) {
                    log.info("Parsed {} on attempt {}", type.getSimpleName(), attempt);
                    return parsed;
                }
                lastProblem = "incomplete answer";
            } catch (JsonProcessingException e) {
                lastProblem = e.getOriginalMessage();
            }
            log.warn("Attempt {} of {} gave an unusable answer: {}", attempt, MAX_ATTEMPTS, lastProblem);
        }
        throw new IllegalStateException("The AI gave no usable answer after " + MAX_ATTEMPTS + " attempts: " + lastProblem);
    }

    private static boolean isAnthropicBadRequest(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getName().equals("com.anthropic.errors.BadRequestException")) {
                return true;
            }
        }
        return false;
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

# Question Generation Strategies

This application supports multiple strategies for generating quiz bowl questions. You can switch between strategies using configuration.

## Available Strategies

### 1. Default Strategy (`default`)

**How it works:**
- Generates question and answer together in a single LLM call
- Uses diversity mandates to encourage variety across questions
- Relies on the LLM's training data and internal knowledge
- Fast and efficient for general topics

**Best for:**
- General knowledge topics
- Historical subjects with stable facts
- When web connectivity is not required
- Faster generation times

**Configuration:**
```yaml
sockbowl:
  ai:
    packetgen:
      strategy: default
```

Or via environment variable:
```bash
export SOCKBOWL_QUESTION_STRATEGY=default
```

---

### 2. Web Search Strategy (`web-search`)

**How it works:**
1. **Phase 1: Fact Gathering**
   - Performs multiple web searches about the topic
   - Gathers general information, current/recent info, and contextual data
   - Builds a comprehensive fact base with verifiable sources

2. **Phase 2: Answer Generation**
   - Analyzes gathered facts to identify interesting, diverse answers
   - Creates a list of potential answers (properly formatted NAQT answer lines)
   - Ensures variety across categories, time periods, and aspects

3. **Phase 3: Question Crafting**
   - For each answer, crafts a pyramidal NAQT question
   - Uses gathered facts to ensure all clues are verifiable
   - Starts with obscure details, progresses to well-known facts

**Best for:**
- Current events or recent topics (2023+)
- Topics requiring factual verification
- Specialized or technical subjects
- Questions that need web-sourced evidence

**Configuration:**
```yaml
sockbowl:
  ai:
    packetgen:
      strategy: web-search
```

Or via environment variable:
```bash
export SOCKBOWL_QUESTION_STRATEGY=web-search
```

**Requirements:**
- MCP servers must be running (Brave Search, Fetch)
- `BRAVE_API_KEY` environment variable must be set
- Internet connectivity required
- Node.js/npx must be installed

---

## Configuration

### Strategy Selection

In `application.yml`:

```yaml
sockbowl:
  ai:
    packetgen:
      question-count: 5
      strategy: web-search  # Options: default, web-search
```

### Environment Variables

```bash
# Set strategy
export SOCKBOWL_QUESTION_STRATEGY=web-search

# Number of questions per packet
export SOCKBOWL_PACKET_QUESTION_COUNT=10

# For web-search strategy
export BRAVE_API_KEY=your-brave-api-key-here
```

---

## Comparison

| Feature | Default Strategy | Web Search Strategy |
|---------|-----------------|---------------------|
| **Speed** | Fast (~5-10s per question) | Slower (~15-30s per question) |
| **Fact Verification** | LLM knowledge only | Web-verified facts |
| **Current Events** | Limited to training cutoff | Up-to-date information |
| **Dependencies** | None | MCP servers, Brave API |
| **Internet Required** | No | Yes |
| **Source Attribution** | No | Yes (from web searches) |
| **Fact Accuracy** | Good | Excellent (verified) |
| **Diversity** | Diversity mandates | Fact-based variety |

---

## Usage Examples

### Example 1: Default Strategy

```java
@Autowired
private QuestionGenerationService questionService;

// Uses default strategy
Packet packet = questionService.generatePacket(
    "Ancient Greek Philosophy",
    "Focus on pre-Socratic thinkers",
    5
);
```

### Example 2: Web Search Strategy

First, set the strategy in `application.yml`:
```yaml
sockbowl.ai.packetgen.strategy: web-search
```

Then generate:
```java
@Autowired
private QuestionGenerationService questionService;

// Uses web-search strategy
Packet packet = questionService.generatePacket(
    "2024 Nobel Prize winners",
    "Focus on scientific achievements",
    5
);
```

The web-search strategy will:
1. Search for "2024 Nobel Prize winners" facts
2. Generate 5 answer candidates (e.g., specific laureates)
3. Craft pyramidal questions for each answer using web facts

### Example 3: Checking Active Strategy

```java
@Autowired
private QuestionGenerationService questionService;

String activeStrategy = questionService.getActiveStrategyName();
log.info("Using strategy: {}", activeStrategy);

QuestionGenerationStrategy strategy = questionService.getActiveStrategy();
// Use strategy directly if needed
```

---

## Strategy Architecture

```
QuestionGenerationService
         |
         v
  [Strategy Selection]
         |
    ┌────┴────┐
    v         v
Default    WebSearch
Strategy   Strategy
    |         |
    |         ├─> 1. Gather Facts (WebSearchService)
    |         ├─> 2. Generate Answers
    |         └─> 3. Craft Questions
    v
  Tossup
```

### Class Structure

- **`QuestionGenerationStrategy`** (interface)
  - `generateTossup(topic, context, existing)`
  - `generatePacket(topic, context, count)`
  - `getStrategyName()`

- **`DefaultQuestionGenerationStrategy`** (component: `defaultStrategy`)
  - Original single-pass generation approach
  - Uses diversity mandates for variety

- **`WebSearchQuestionGenerationStrategy`** (component: `webSearchStrategy`)
  - Three-phase approach (facts → answers → questions)
  - Integrates with `WebSearchService` for fact gathering

- **`QuestionGenerationService`**
  - Facade that delegates to active strategy
  - Configured via `sockbowl.ai.packetgen.strategy`

---

## Adding New Strategies

To create a new strategy:

### 1. Implement the Interface

```java
@Component("myStrategy")
@Slf4j
public class MyCustomStrategy implements QuestionGenerationStrategy {

    @Override
    public String getStrategyName() {
        return "my-custom";
    }

    @Override
    public Tossup generateTossup(String topic, String context, List<Tossup> existing) {
        // Your logic here
    }

    @Override
    public Packet generatePacket(String topic, String context, int count) {
        // Your logic here
    }
}
```

### 2. Register in QuestionGenerationService

Update `QuestionGenerationService` constructor:

```java
public QuestionGenerationService(
        @Qualifier("defaultStrategy") QuestionGenerationStrategy defaultStrategy,
        @Qualifier("webSearchStrategy") QuestionGenerationStrategy webSearchStrategy,
        @Qualifier("myStrategy") QuestionGenerationStrategy myCustomStrategy,
        @Value("${sockbowl.ai.packetgen.strategy:default}") String configuredStrategy) {

    this.strategies = Map.of(
            "default", defaultStrategy,
            "web-search", webSearchStrategy,
            "my-custom", myCustomStrategy  // Add your strategy
    );

    this.activeStrategy = strategies.getOrDefault(configuredStrategy, defaultStrategy);
    this.strategyName = configuredStrategy;
}
```

### 3. Use the Strategy

```yaml
sockbowl.ai.packetgen.strategy: my-custom
```

---

## Logging

Both strategies provide detailed logging:

### Default Strategy Logs
```
=== Starting Tossup Generation (Default Strategy) ===
Topic: Ancient Rome
Building structured prompt incorporating best practices
LLM call attempt 1 of 10
Successfully parsed JSON on attempt 1
```

### Web Search Strategy Logs
```
=== Starting Packet Generation (Web Search Strategy) ===
STEP 1: Gathering factual sources about: Quantum Computing
Search 1: General information
Search 2: Recent information
STEP 2: Generating list of 5 potential answers
Generated 5 answers: [ANSWER: Shor's algorithm, ...]
STEP 3: Crafting questions for each answer
Crafting question 1 of 5 for answer: ANSWER: Shor's algorithm
```

---

## Performance Considerations

### Default Strategy
- **Time per question:** ~5-10 seconds
- **API calls:** 1 LLM call per question
- **Rate limits:** Ollama local rate limits only

### Web Search Strategy
- **Time per question:** ~15-30 seconds
- **API calls:**
  - 3 web searches per packet (initial fact gathering)
  - 1 LLM call for answer generation
  - 1 LLM call per question for crafting
- **Rate limits:**
  - Brave Search API limits (free tier: 2000 queries/month)
  - Ollama local rate limits

**Tip:** For web-search strategy, consider caching web search results for repeated topics.

---

## Troubleshooting

### Strategy not changing

**Problem:** Strategy still uses default even after changing config

**Solution:**
1. Restart the application
2. Check logs for: `QuestionGenerationService initialized with strategy: [name]`
3. Verify `application.yml` syntax is correct

### Web search strategy failing

**Problem:** Errors when using web-search strategy

**Solutions:**
1. Ensure `BRAVE_API_KEY` is set
2. Check MCP servers are running (check logs on startup)
3. Verify internet connectivity
4. Check Brave API quota hasn't been exceeded

### Questions lack diversity

**Problem:** Questions are too similar

**Solutions:**
- **Default strategy:** Already uses diversity mandates
- **Web search strategy:** Provide more specific `additionalContext` to guide variety
- Try different topics or adjust prompts in strategy implementation

---

## Best Practices

### For Default Strategy
- Works best with well-established, historical topics
- Provide clear additional context for better results
- Use when speed is important

### For Web Search Strategy
- Ideal for current events, recent discoveries, contemporary topics
- Provide specific context to guide web searches
- Allow extra time for fact gathering
- Monitor Brave API usage to stay within quotas

### General
- Use environment variables for easy strategy switching
- Monitor logs to understand generation process
- Test both strategies for your use case
- Consider strategy based on topic characteristics:
  - Historical/Classical → Default
  - Recent/Current → Web Search
  - Technical/Specialized → Web Search (for verification)

---

## Future Enhancements

Potential new strategies:
- **Database Strategy**: Query internal knowledge base first
- **Hybrid Strategy**: Combine default + web search
- **RAG Strategy**: Use vector store for retrieval
- **Multi-LLM Strategy**: Use different models for different phases
- **Collaborative Strategy**: Multiple LLMs generate + critique

To request a new strategy, file an issue with your use case!

# MCP Web Search Integration

This project integrates web search capabilities using MCP (Model Context Protocol), allowing the AI to search the web for current information and context.

## Overview

The system uses two MCP servers:
1. **Brave Search** - Web search using Brave Search API
2. **Fetch** - Fetch and parse content from specific URLs

These tools are automatically available to the AI via Spring AI's MCP integration.

## Architecture

```
┌─────────────────────────────────────┐
│         ChatClient (AI)             │
│   - Automatically calls MCP tools   │
│   - brave_web_search                │
│   - fetch                           │
└──────────────┬──────────────────────┘
               │
               v
┌──────────────────────────────────────┐
│      WebSearchService                │
│   - searchWithAI()                   │
│   - braveSearch()                    │
│   - fetchUrl()                       │
│   - getWebContext()                  │
└──────────────┬───────────────────────┘
               │
               v
┌──────────────────────────────────────┐
│      MCP Client & Tool Manager       │
│   - Manages MCP server connections   │
│   - Routes tool calls                │
└──────────────┬───────────────────────┘
               │
       ┌───────┴────────┐
       v                v
┌─────────────┐  ┌──────────────┐
│ Brave Search│  │    Fetch     │
│ MCP Server  │  │  MCP Server  │
└─────────────┘  └──────────────┘
```

## Setup

### 1. Install Node.js and npx

MCP servers are npm packages that run via `npx`. Ensure you have Node.js installed:

```bash
node --version  # Should be v18 or higher
npx --version
```

### 2. Get Brave Search API Key

1. Sign up at [Brave Search API](https://brave.com/search/api/)
2. Get your API key (free tier available)
3. Set environment variable:

```bash
export BRAVE_API_KEY=your-brave-api-key-here
```

Or add to your `.env` file:
```env
BRAVE_API_KEY=your-brave-api-key-here
```

### 3. Configuration

The MCP servers are configured in `application.yml`:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        servers:
          brave-search:
            command: npx
            args:
              - '-y'
              - '@modelcontextprotocol/server-brave-search'
            env:
              BRAVE_API_KEY: '${BRAVE_API_KEY:your-brave-api-key-here}'
          fetch:
            command: npx
            args:
              - '-y'
              - '@modelcontextprotocol/server-fetch'
```

## Usage

### Automatic (AI-Powered)

The AI will automatically use MCP tools when needed. Just ask questions or generate content:

```java
@Service
public class MyService {
    private final WebSearchService webSearchService;

    public MyService(WebSearchService webSearchService) {
        this.webSearchService = webSearchService;
    }

    public void example() {
        // AI automatically uses web search when needed
        String info = webSearchService.searchWithAI(
            "What are the latest developments in quantum computing in 2024?"
        );
        System.out.println(info);
    }
}
```

### Manual (Direct Tool Calls)

You can also directly call specific MCP tools:

#### Brave Search

```java
// Simple search
String results = webSearchService.braveSearch("Spring AI MCP integration");

// Search with custom result count
String results = webSearchService.braveSearch("Claude AI features", 15);
```

#### Fetch URL

```java
String content = webSearchService.fetchUrl("https://example.com/article");
```

### Convenience Methods

#### Get Web Context

```java
// Get comprehensive information about a topic
String context = webSearchService.getWebContext("Recent Nobel Prize winners");
```

#### Get Current Information

```java
// Focus on recent/current information
String currentInfo = webSearchService.getCurrentInfo("AI developments");
```

#### Verify Facts

```java
// Verify a claim with sources
String verification = webSearchService.verifyFact(
    "The speed of light is approximately 299,792 km/s"
);
```

## Integration with Question Generation

The AI will automatically use web search when generating quiz bowl questions:

### Example 1: Current Events

```java
Tossup tossup = questionGenerationService.generateTossup(
    "2024 Nobel Prize winners",
    "Focus on the most recent awards",
    existingTossups
);
```

The AI will automatically:
1. Search the web for "2024 Nobel Prize winners"
2. Gather accurate, current information
3. Generate a factually correct question with proper citations

### Example 2: Enhanced Context

```java
@Service
public class EnhancedQuestionService {
    private final QuestionGenerationService questionService;
    private final WebSearchService webSearchService;

    public Tossup generateWithWebContext(String topic) {
        // Manually get web context first
        String webContext = webSearchService.getWebContext(topic);

        // Generate question with enriched context
        return questionService.generateTossup(
            topic,
            "Additional context: " + webContext,
            List.of()
        );
    }

    public Tossup generateCurrent(String topic) {
        // Get current information
        String current = webSearchService.getCurrentInfo(topic);

        // Generate with current info
        return questionService.generateTossup(
            topic,
            "Recent developments: " + current,
            List.of()
        );
    }
}
```

## MCP Tools Available

### brave_web_search

**Description:** Search the web using Brave Search API

**Parameters:**
- `query` (string, required): The search query
- `count` (integer, optional): Number of results (default: 10, max: 20)

**Returns:** JSON with search results including:
- Web results (title, description, URL)
- News results
- Videos
- Infobox data

### fetch

**Description:** Fetch and parse content from a URL

**Parameters:**
- `url` (string, required): The URL to fetch

**Returns:** Parsed content from the URL

## Configuration Options

### Enable/Disable MCP

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: false  # Set to false to disable MCP
```

### Add More MCP Servers

You can add additional MCP servers:

```yaml
spring:
  ai:
    mcp:
      client:
        servers:
          brave-search:
            # ... existing config ...
          fetch:
            # ... existing config ...
          my-custom-server:
            command: npx
            args:
              - '-y'
              - '@my-org/my-mcp-server'
            env:
              API_KEY: '${MY_API_KEY}'
```

Popular MCP servers:
- `@modelcontextprotocol/server-brave-search` - Brave Search
- `@modelcontextprotocol/server-fetch` - Web fetch
- `@modelcontextprotocol/server-filesystem` - File system access
- `@modelcontextprotocol/server-postgres` - PostgreSQL access
- `@modelcontextprotocol/server-github` - GitHub API

See [MCP Servers Directory](https://github.com/modelcontextprotocol/servers) for more.

## API Reference

### WebSearchService

#### `searchWithAI(String query)`
Let the AI search the web and synthesize results.
- **Parameters:** query - The search query or question
- **Returns:** AI-generated response incorporating web search results

#### `braveSearch(String query)`
Direct Brave Search (default 10 results).
- **Parameters:** query - The search query
- **Returns:** Raw search results as JSON string

#### `braveSearch(String query, int count)`
Direct Brave Search with custom result count.
- **Parameters:**
  - query - The search query
  - count - Number of results (max 20)
- **Returns:** Raw search results as JSON string

#### `fetchUrl(String url)`
Fetch content from a specific URL.
- **Parameters:** url - The URL to fetch
- **Returns:** Parsed content from the URL

#### `getWebContext(String topic)`
Get comprehensive contextual information about a topic.
- **Parameters:** topic - The topic to research
- **Returns:** Formatted context with sources

#### `getCurrentInfo(String topic)`
Get recent/current information about a topic.
- **Parameters:** topic - The topic to search
- **Returns:** Recent information and context

#### `verifyFact(String claim)`
Verify a fact or claim using web search.
- **Parameters:** claim - The fact or claim to verify
- **Returns:** Verification result with sources

## Troubleshooting

### MCP Server Not Starting

**Error:** "Failed to start MCP server"

**Solutions:**
1. Ensure Node.js is installed: `node --version`
2. Check npx is available: `npx --version`
3. Verify the MCP package name is correct
4. Check logs for specific error messages

### API Key Issues

**Error:** "API key not configured" or "Invalid API key"

**Solutions:**
1. Verify `BRAVE_API_KEY` environment variable is set
2. Check the API key is valid at [Brave Search API](https://brave.com/search/api/)
3. Ensure the key has not exceeded rate limits

### No Results Returned

**Possible causes:**
1. Query is too vague or specific
2. Network connectivity issues
3. Rate limit exceeded
4. MCP server crashed (check logs)

**Solutions:**
1. Try a different query
2. Check application logs for detailed error messages
3. Restart the application to restart MCP servers
4. Verify API quota at Brave dashboard

### AI Not Using Tools

**Problem:** AI responses don't include web search results

**Solutions:**
1. Be explicit in your prompt: "Search the web for..."
2. Ask about current/recent topics
3. Check MCP is enabled in `application.yml`
4. Verify MCP servers started successfully (check logs)
5. Ensure `ToolCallbackProvider` is properly configured

## Performance Notes

- **Startup Time:** MCP servers start when the application starts. First request may be slower.
- **Caching:** Brave Search results are not cached by default. Consider implementing caching for repeated queries.
- **Rate Limits:** Brave Search free tier has rate limits. See [pricing](https://brave.com/search/api/pricing/) for details.
- **Network:** MCP tools require internet connectivity.

## Security Considerations

- **API Keys:** Never commit API keys to version control. Use environment variables.
- **URL Fetching:** The fetch tool can access any URL. Validate URLs in production.
- **Output Validation:** Web search results should be validated before using in production contexts.

## Example: Complete Integration

```java
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionGenerationService questionService;
    private final WebSearchService webSearchService;

    public QuestionController(
            QuestionGenerationService questionService,
            WebSearchService webSearchService) {
        this.questionService = questionService;
        this.webSearchService = webSearchService;
    }

    @PostMapping("/generate-current")
    public Tossup generateCurrentEventQuestion(@RequestParam String topic) {
        // Get current information about the topic
        String currentInfo = webSearchService.getCurrentInfo(topic);

        // Generate question with current context
        return questionService.generateTossup(
            topic,
            "Use this current information: " + currentInfo,
            List.of()
        );
    }

    @PostMapping("/generate-with-verification")
    public Tossup generateVerifiedQuestion(@RequestParam String topic) {
        // Let AI handle everything (including web search for verification)
        return questionService.generateTossup(
            topic,
            "Verify all facts using web search before generating the question",
            List.of()
        );
    }

    @GetMapping("/search")
    public String searchWeb(@RequestParam String query) {
        // Direct web search
        return webSearchService.braveSearch(query, 10);
    }

    @GetMapping("/context")
    public String getContext(@RequestParam String topic) {
        // Get formatted context
        return webSearchService.getWebContext(topic);
    }
}
```

## Further Reading

- [Spring AI MCP Documentation](https://docs.spring.io/spring-ai/reference/api/clients/mcp.html)
- [Model Context Protocol Specification](https://modelcontextprotocol.io/)
- [MCP Servers Directory](https://github.com/modelcontextprotocol/servers)
- [Brave Search API Documentation](https://brave.com/search/api/)

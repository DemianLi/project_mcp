# Spring AI 2.0.x MCP Tool API Research

Research on how Spring AI 2.0.x declares MCP Tools, with sources verified from primary documentation.

**Date:** September 4, 2026  
**Version Scope:** Spring AI 2.0.x (includes 2.0.0 and later 2.0.x releases)

---

## 1. Tool Registration

**Question:** Is it annotation-driven (@Tool-style on a bean method), bean-driven (returning a specification object), or both? Name the actual types.

### Answer: Annotation-Driven with `@McpTool`

Spring AI 2.0.x uses **annotation-driven tool registration** via the **`@McpTool`** annotation on methods within Spring-managed beans.

**Annotation Types:**
- **`@McpTool`** – Declares a method as an MCP tool
  - Attributes: `name`, `description`, `title`, `generateOutputSchema`, `annotations`, `metaProvider`
  - Full type: `org.springframework.ai.mcp.annotation.McpTool`
  - Source: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html

- **`@McpToolParam`** – Annotates tool method parameters
  - Attributes: `description`, `required`
  - Full type: `org.springframework.ai.mcp.annotation.McpToolParam`
  - Source: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html

**Registration Process:**
Tools are **automatically registered** via Spring Boot auto-configuration when:
1. A method is annotated with `@McpTool`
2. The enclosing class is a Spring `@Component` (or `@Service`, `@Repository`)
3. Annotation scanning is enabled (default: `spring.ai.mcp.server.annotation-scanner.enabled: true`)

**Minimal Example:**
```java
@Component
public class CalculatorTools {
    
    @McpTool(name = "add", description = "Add two numbers together")
    public int add(
            @McpToolParam(description = "First number", required = true) int a,
            @McpToolParam(description = "Second number", required = true) int b) {
        return a + b;
    }
}
```

**Under the Hood:**
- `@McpTool` methods are internally wrapped as **`SyncMcpToolMethodCallback`** or **`AsyncMcpToolMethodCallback`** objects
- These callbacks implement the MCP protocol's `CallToolRequest` → `CallToolResult` contract
- Source: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html

---

## 2. Input Schema

**Question:** How is the input schema declared — derived from the method signature, or written by hand as a Map<String, Object>?

### Answer: Automatically Derived from Method Signature

Spring AI 2.0.x **automatically generates the input schema from the method signature** using JSON Schema 2020-12.

**Schema Generation:**
- Parameter names → JSON Schema `properties`
- `@McpToolParam(description = "...")` → property descriptions
- `@McpToolParam(required = true/false)` → `required` array entries
- Parameter types (int, String, etc.) → JSON Schema types
- No manual schema writing required

**Example:**
```java
@McpTool(description = "Get weather for a city")
public String getWeather(
        @McpToolParam(description = "City name") String city,
        @McpToolParam(description = "Time in ISO-8601 format", required = false) String at) {
    return weatherService.fetch(city, at);
}
```

**Generated Input Schema (implied):**
```json
{
  "type": "object",
  "properties": {
    "city": {
      "type": "string",
      "description": "City name"
    },
    "at": {
      "type": "string",
      "description": "Time in ISO-8601 format"
    }
  },
  "required": ["city"]
}
```

**Schema Customization:**
Spring AI respects parameter-level annotations in this precedence order:
1. `@McpToolParam(description = "…")` – Spring AI specific
2. `@JsonPropertyDescription("…")` – Jackson
3. `@Schema(description = "…")` – Swagger OpenAPI

Source: https://docs.spring.io/spring-ai/reference/api/tools.html

**Dynamic Schema Support:**
- `@McpTool` methods may receive a **`CallToolRequest`** parameter to access raw request data for dynamic schema handling
- Source: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-special-params.adoc

---

## 3. Return and Error Handling

**Question:** How does a Tool return content, and what does an error return look like?

### Answer: Via Method Return Types or `CallToolResult`

Spring AI 2.0.x provides two patterns for tool return values:

#### Pattern A: Typed Return (Automatic Wrapping)

The `@McpTool` method returns a primitive or object type, and Spring AI wraps it automatically:

```java
@McpTool(description = "Add two numbers")
public int add(
        @McpToolParam(required = true) int a,
        @McpToolParam(required = true) int b) {
    return a + b;  // int is wrapped in CallToolResult
}
```

Return value is converted to text and wrapped in:
```java
new McpSchema.CallToolResult(
    List.of(new McpSchema.TextContent(result.toString())),
    false  // isError = false
)
```

#### Pattern B: Explicit `CallToolResult` (Full Control)

The `@McpTool` method returns a **`CallToolResult`** object for fine-grained control over response structure:

**Success Response:**
```java
@McpTool(description = "Look up user data")
public CallToolResult lookupUser(String userId) {
    // ... fetch user ...
    return CallToolResult.builder()
        .addTextContent("User: " + user.getName())
        .structuredContent(user)  // Optional structured output
        .build();
}
```

**Error Response:**
```java
@McpTool(description = "Process data")
public String processData(String input) {
    if (input == null || input.isEmpty()) {
        throw new RuntimeException("Input cannot be empty");  // Auto-converted to error
    }
    return "Processed: " + input;
}
```

**How Errors Work in Spring AI 2.0:**

Spring AI 2.0 changed exception handling for `@McpTool` methods (from 1.x):

| Exception Type | Behavior | Result |
|---|---|---|
| `RuntimeException` (not `McpError`) | Caught by Spring AI | Converted to error `CallToolResult` with `isError = true` |
| Declared checked exception | Bubbles up | Tool call fails entirely (not conveyed to model) |
| `Error` subtype | Bubbles up | Tool call fails entirely |
| `McpError` | Bubbles up | Protocol-level error signal |

**Error Result Structure:**
```json
{
  "resultType": "complete",
  "content": [
    {
      "type": "text",
      "text": "Input cannot be empty"
    }
  ],
  "isError": true
}
```

**In Java:**
```java
new McpSchema.CallToolResult(
    List.of(new McpSchema.TextContent("Error: " + exception.getMessage())),
    true  // isError = true
)
```

**Recommended Pattern for Errors:**
Throw `RuntimeException` to send the error message to the LLM for recovery:
```java
@McpTool(description = "Look up order")
public String lookupOrder(String orderId) {
    if (orderId == null) {
        throw new RuntimeException("Order ID is required");  // Reaches model
    }
    return service.findOrder(orderId);
}
```

Do NOT throw checked exceptions if you want the model to see and recover from the error.

**Sources:**
- https://docs.spring.io/spring-ai/reference/upgrade-notes.html (exception handling in 2.0)
- https://github.com/spring-projects/spring-ai/issues/4534 (CallToolResult structure)
- https://modelcontextprotocol.io/specification/2026-07-28/server/tools (MCP specification)

---

## 4. Configuration Properties

**Question:** Which `spring.ai.mcp.server.*` keys configure a stdio server under Spring AI 2.0.x?

### Answer: STDIO Server Configuration Properties

Spring AI 2.0.x exposes STDIO MCP server configuration via these properties:

#### Core Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `spring.ai.mcp.server.type` | String | `SYNC` | Server type: `SYNC` or `ASYNC` |
| `spring.ai.mcp.server.stdio` | Boolean | `true` (when STDIO transport used) | Enable STDIO transport |
| `spring.ai.mcp.server.protocol` | String | `STREAMABLE` | Transport protocol: `STREAMABLE`, `SSE` (deprecated), or `STATELESS` |
| `spring.ai.mcp.server.annotation-scanner.enabled` | Boolean | `true` | Enable automatic `@McpTool` detection and registration |

**Source:** https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html

#### STDIO Configuration Example

To enable a synchronous STDIO MCP server:

```yaml
spring:
  ai:
    mcp:
      server:
        type: SYNC
        annotation-scanner:
          enabled: true
```

**Dependency Required:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server</artifactId>
</dependency>
```

**Source:** https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html

#### HTTP Transport Configuration

For non-STDIO (HTTP) transports:

```yaml
spring:
  ai:
    mcp:
      server:
        type: SYNC
        protocol: STREAMABLE  # or STATELESS
        annotation-scanner:
          enabled: true
```

**Dependency Required:**
```xml
<!-- For WebMVC (Spring MVC) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>

<!-- OR for WebFlux (Reactive) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
</dependency>
```

**Source:** https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html

#### Default Behavior

- **STDIO transport:** Enabled automatically with `spring-ai-starter-mcp-server` dependency
- **HTTP endpoint:** Default endpoint is `POST /mcp` (exposed as unauthenticated JSON-RPC)
- **Capabilities:** Tools, Resources, Prompts, Completions, Logging, Progress, Ping (all enabled by default)
- **Annotation scanning:** Enabled by default; scans for `@McpTool`, `@McpResource`, `@McpPrompt`

**Source:** https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html

#### Security Warning

> The HTTP-based MCP endpoints (SSE, Streamable-HTTP, and Stateless) are **unauthenticated by default**. You must implement a security layer using Spring Security or [MCP Security](https://github.com/spring-ai-community/mcp-security) before exposing beyond localhost.

**Source:** https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html

---

## Minimal Complete Example: Spring AI 2.0 MCP Tool

```java
@SpringBootApplication
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}

@Component
public class WeatherTools {
    
    @McpTool(
        name = "get_weather",
        description = "Get the current weather for a location",
        title = "Weather Lookup"
    )
    public String getWeather(
            @McpToolParam(description = "City name or zip code", required = true) String location,
            @McpToolParam(description = "Temperature unit: C or F", required = false) String unit) {
        
        if (location == null || location.trim().isEmpty()) {
            throw new RuntimeException("Location is required");
        }
        
        // Fetch weather from service
        String temp = "72°F";
        String conditions = "Partly cloudy";
        
        return String.format("Weather in %s: %s, %s", location, temp, conditions);
    }
    
    @McpTool(name = "get_forecast", description = "Get 5-day forecast")
    public CallToolResult getForecast(
            @McpToolParam(description = "City name", required = true) String location) {
        
        Map<String, String> forecast = Map.of(
            "today", "72°F, Sunny",
            "tomorrow", "68°F, Rainy",
            "day3", "70°F, Cloudy"
        );
        
        return CallToolResult.builder()
            .addTextContent("5-day forecast for " + location)
            .structuredContent(forecast)
            .build();
    }
}
```

**application.yml:**
```yaml
spring:
  ai:
    mcp:
      server:
        type: SYNC
        annotation-scanner:
          enabled: true
```

**pom.xml:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server</artifactId>
</dependency>
```

---

## Version Verification

- **Spring AI 2.0.x** is the current release line, and supports Spring Boot 4.0.x and
  4.1.x. Verified: https://docs.spring.io/spring-ai/reference/getting-started.html
- **Spring Boot 4.1.1** supports Java 17 through 26, so Java 25 is in range. Verified:
  https://docs.spring.io/spring-boot/system-requirements.html
- **`@McpTool` / `@McpToolParam` exist and the example above is verbatim from the docs.**
  Verified: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html
- **Which MCP Java SDK version Spring AI 2.0.x bundles: could not confirm.** Sources
  conflicted (1.0.0 vs 2.0.0). It does not block the first Tool — the annotations are
  the surface we code against — but check `mvn dependency:tree` once the project exists
  rather than trusting either number.

---

## Additional References

1. **Tool Calling (General Spring AI)**: https://docs.spring.io/spring-ai/reference/api/tools.html
2. **MCP Server Annotations**: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html
3. **MCP Server Boot Starter**: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
4. **MCP Specification (Tools)**: https://modelcontextprotocol.io/specification/2026-07-28/server/tools
5. **Spring AI Upgrade Notes**: https://docs.spring.io/spring-ai/reference/upgrade-notes.html
6. **MCP Annotations (Community)**: https://github.com/spring-ai-community/mcp-annotations
7. **Issue #4534 (CallToolResult)**: https://github.com/spring-projects/spring-ai/issues/4534
8. **Issue #4488 (Exception Handling)**: https://github.com/spring-projects/spring-ai/issues/4488

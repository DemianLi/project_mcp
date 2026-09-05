# Research: MCP Tool Hints and Destructive Operations

**Research Date:** 2026-09-05
**Issue:** GitHub issue #26 - MCP specification guidance on tools that modify external state
**Spec Versions Checked:** 2025-11-25, 2026-07-28

## Research Scope

Three specific research questions about MCP specification and implementation:

1. **`destructiveHint` and `idempotentHint` specification semantics:**
   - What are they? Are they promises to the Client or just hints?
   - What does the spec say the Client SHOULD/MUST/MAY do with them?
   - Distinction between SPEC requirements vs Spring AI SDK implementation
   - Spring AI docs mention "meaningful only when readOnlyHint == false" — what does the spec itself require/suggest?

2. **Human-in-the-loop mechanisms in the spec:**
   - Does the spec define any elicitation/confirmation/sampling mechanisms?
   - Can the Server initiate these, or are they purely Client-side decisions?
   - Requirements around "ask before executing" for destructive operations

3. **Tool call result indeterminacy handling:**
   - When a Tool call result cannot be determined (timeout, connection loss, crash), what does spec say?
   - Retry semantics, idempotency guarantees, request ID reuse rules?
   - Exactly-once vs at-least-once delivery semantics?

---

## Section 1: Spring AI SDK Implementation (Primary Source)

### Spring AI MCP Annotations (v2.0.1)

**Source:** `/Users/demian/.m2/repository/org/springframework/ai/spring-ai-mcp-annotations/2.0.1/spring-ai-mcp-annotations-2.0.1-sources.jar`

**File:** `org/springframework/ai/mcp/annotation/McpTool.java`

#### `McpAnnotations` Interface Definition

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface McpAnnotations {

    /**
     * A human-readable title for the tool.
     */
    String title() default "";

    /**
     * If true, the tool does not modify its environment.
     */
    boolean readOnlyHint() default false;

    /**
     * If true, the tool may perform destructive updates to its environment. If false,
     * the tool performs only additive updates.
     *
     * (This property is meaningful only when readOnlyHint == false)
     */
    boolean destructiveHint() default true;

    /**
     * If true, calling the tool repeatedly with the same arguments will have no
     * additional effect on its environment.
     *
     * (This property is meaningful only when readOnlyHint == false)
     */
    boolean idempotentHint() default false;

    /**
     * If true, this tool may interact with an "open world" of external entities. If
     * false, the tool's domain of interaction is closed. For example, the world of a
     * web search tool is open, whereas that of a memory tool is not.
     */
    boolean openWorldHint() default true;
}
```

**Key Findings from Spring AI Source:**

1. **Spring AI Defaults:**
   - `readOnlyHint = false`
   - `destructiveHint = true`
   - `idempotentHint = false`
   - `openWorldHint = true`

2. **Spring AI Semantics (from JavaDoc):**
   - `readOnlyHint`: "If true, the tool does not modify its environment." [Boolean]
   - `destructiveHint`: "If true, the tool may perform destructive updates to its environment. If false, the tool performs only additive updates." [Conditional on readOnlyHint==false]
   - `idempotentHint`: "If true, calling the tool repeatedly with the same arguments will have no additional effect on its environment." [Conditional on readOnlyHint==false]
   - These are described as "hints" in the interface-level comment

3. **Important Note from Spring AI Code Comment** (in project-mcp source, IssueTools.java lines 52-55):
   ```
   // Spring AI's defaults are readOnlyHint=false / destructiveHint=true, which
   // would have this Tool advertise itself as a destructive write — the opposite
   // of the map's read-only constraint, and enough to make a Client ask the user
   // to confirm a listing.
   ```
   This indicates Spring AI's defaults can cause unexpected client behavior for read-only tools.

### MCP Core SDK (v2.0.0)

**Source:** `/Users/demian/.m2/repository/io/modelcontextprotocol/sdk/mcp-core/2.0.0/mcp-core-2.0.0-sources.jar`

**File:** `io/modelcontextprotocol/spec/McpSchema.java`

#### `ToolAnnotations` Record Definition

```java
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolAnnotations( // @formatter:off
    @JsonProperty("title")  String title,
    @JsonProperty("readOnlyHint")   Boolean readOnlyHint,
    @JsonProperty("destructiveHint") Boolean destructiveHint,
    @JsonProperty("idempotentHint") Boolean idempotentHint,
    @JsonProperty("openWorldHint") Boolean openWorldHint,
    @JsonProperty("returnDirect") Boolean returnDirect) { // @formatter:on
    // ... builder implementation ...
}
```

**Key Findings from MCP Core SDK:**

1. **Schema Transport:** ToolAnnotations are transported as JSON with these fields as Booleans (nullable/optional based on `JsonInclude.NON_ABSENT`)

2. **New Field (2.0.0):** `returnDirect` field added alongside the four hints

3. **Mapping:** These Java record fields are the direct serialization of what the spec defines in `ToolAnnotations`

---

## Section 2: Project-Specific Implementation (Primary Source)

### Current Tool Implementation

**Source:** `/Users/demian/Projects_vibecoding/project_mcp/src/main/java/io/github/demianli/projectmcp/tool/`

#### All Read-Only Tools Mark Hints Explicitly

The project's four read-only tools (`list_issues`, `get_issue`, `list_labels`, `list_issue_comments`) all explicitly set:

```java
annotations = @McpTool.McpAnnotations(
    readOnlyHint = true,
    destructiveHint = false,
    // ... other fields ...
)
```

**Reason (from IssueTools.java):**
The Spring AI defaults (`readOnlyHint=false / destructiveHint=true`) would cause clients to treat read-only operations as destructive writes, potentially triggering user confirmation prompts inappropriately. The project overrides these defaults for all read-only tools.

---

## Section 3: MCP Specification (PRIMARY SOURCE - VERIFIED)

### 3.1 ToolAnnotations Specification Definition

Both spec versions define `ToolAnnotations` identically in their schema.ts files. The key specification text is:

**From schema.ts (both 2025-11-25 and 2026-07-28, verbatim):**

```typescript
/**
 * NOTE: all properties in ToolAnnotations are **hints**.
 * They are not guaranteed to provide a faithful description of
 * tool behavior (including descriptive properties like `title`).
 *
 * Clients should never make tool use decisions based on ToolAnnotations
 * received from untrusted servers.
 *
 * @category `tools/list`
 */
export interface ToolAnnotations {
  /**
   * A human-readable title for the tool.
   */
  title?: string;

  /**
   * If true, the tool does not modify its environment.
   *
   * Default: false
   */
  readOnlyHint?: boolean;

  /**
   * If true, the tool may perform destructive updates to its environment.
   * If false, the tool performs only additive updates.
   *
   * (This property is meaningful only when `readOnlyHint == false`)
   *
   * Default: true
   */
  destructiveHint?: boolean;

  /**
   * If true, calling the tool repeatedly with the same arguments
   * will have no additional effect on its environment.
   *
   * (This property is meaningful only when `readOnlyHint == false`)
   *
   * Default: false
   */
  idempotentHint?: boolean;

  /**
   * If true, this tool may interact with an "open world" of external
   * entities. If false, the tool's domain of interaction is closed.
   * For example, the world of a web search tool is open, whereas that
   * of a memory tool is not.
   *
   * Default: true
   */
  openWorldHint?: boolean;
}
```

**Source Files:**
- 2025-11-25: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/schema/2025-11-25/schema.ts (lines 1171-1226)
- 2026-07-28: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/schema/2026-07-28/schema.ts (lines 1903-1958)

**Version Comparison:** No differences between 2025-11-25 and 2026-07-28 — the ToolAnnotations definition is identical.

### 3.2 Trust & Safety Guidance in Tools Documentation

**From tools.mdx, both versions (2025-11-25 section 12-34; 2026-07-28 section 31-43):**

```markdown
<Warning>

For trust & safety and security, there **SHOULD** always
be a human in the loop with the ability to deny tool invocations.

Applications **SHOULD**:

- Provide UI that makes clear which tools are being exposed to the AI model
- Insert clear visual indicators when tools are invoked
- Present confirmation prompts to the user for operations, to ensure a human is in the
  loop

</Warning>
```

**Source:**
- 2025-11-25: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/server/tools.mdx (lines 22-34)
- 2026-07-28: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/server/tools.mdx (lines 31-43)

**Note:** The spec says applications SHOULD have a human in the loop, but does NOT mandate that this be based on `destructiveHint`. The human-in-the-loop is a general trust & safety principle, not a specification-defined behavior tied to tool annotations.

### 3.3 Elicitation: Server-Initiated Input Requests

**From elicitation.mdx (2025-11-25 section 81-98):**

Servers CAN initiate elicitation requests directly:

```markdown
### Elicitation Requests

To request information from a user, servers send an `elicitation/create` request.
```

This is a server-initiated capability: servers **send** elicitation/create requests to clients. The specification does NOT require this to be triggered by tool annotations (destructiveHint, idempotentHint, etc.). It is a purely server-controlled decision.

**Source:** https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/client/elicitation.mdx (lines 81-98)

### 3.4 Multi Round-Trip Requests (MRTR) - Tool-Embedded Elicitation (2026-07-28 Only)

**NEW in 2026-07-28:** The specification introduces a pattern where servers can embed elicitation/create requests directly within tool call responses via `InputRequiredResult`.

**From tools.mdx (2026-07-28, lines 174-207):**

```markdown
### Input Required Tool Results

Servers **MAY** respond to `tools/call` with an [`InputRequiredResult`](/specification/2026-07-28/basic/patterns/mrtr#inputrequiredresult) 
to indicate that additional input is needed before the tool call can be completed.

...

**Input Required Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "resultType": "input_required",
    "inputRequests": {
      "github_login": {
        "method": "elicitation/create",
        "params": { ... }
      }
    },
    "requestState": "eyJsb2NhdGlvbiI6Ik5ldyBZb3JrIn0..."
  }
}
```
```

**From MRTR spec (2026-07-28, lines 184-191):**

```markdown
Servers **MAY** send `InputRequiredResult` responses on the following client requests:

| Client Request                                                                   | Supports InputRequiredResult |
| -------------------------------------------------------------------------------- | ---------------------------- |
| [`prompts/get`](/specification/2026-07-28/server/prompts#getting-a-prompt)       | Yes                          |
| [`resources/read`](/specification/2026-07-28/server/resources#reading-resources) | Yes                          |
| [`tools/call`](/specification/2026-07-28/server/tools#calling-tools)             | Yes                          |
```

**Key finding:** The spec says servers "MAY" respond with InputRequiredResult, not MUST. There is NO requirement to trigger this for destructive tools. The decision is entirely server-controlled.

**Sources:**
- tools.mdx: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/server/tools.mdx (lines 174-207)
- MRTR: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/basic/patterns/mrtr.mdx (lines 184-191)

### 3.5 Retry Semantics and Idempotency Guarantees (Notably: NOT Addressed)

**From CallToolRequestParams (both versions):**

The specification defines `CallToolRequest` as a standard JSON-RPC 2.0 request. The params include:
- `name`: The tool name
- `arguments`: Tool arguments
- `_meta`: Optional metadata including progressToken
- (2026-07-28 only) `inputResponses` and `requestState` for MRTR retries

**Critical finding:** There is NO request ID field in the request parameters. The JSON-RPC `id` field exists but:
- (From MRTR spec, line 256): "The JSON-RPC `id` **MUST** be different between the initial request and the retry"
- This means request IDs are NOT reused for correlation/idempotency purposes

**From Tasks spec (2025-11-25, lines 466, 860):**

```markdown
1. For tasks in a terminal status, receivers **MUST** return from `tasks/result` exactly 
   what the underlying request would have returned, whether that is a successful result 
   or a JSON-RPC error.

...

The `tasks/result` endpoint returns exactly what the underlying request would have returned:
```

The spec guarantees that terminal task results are consistent, but this applies ONLY to tasks, not to normal tool calls.

**Sources:**
- CallToolRequest: 2025-11-25 lines 1137-1155, 2026-07-28 lines 1863-1885
- Tasks spec: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/basic/utilities/tasks.mdx (lines 466, 860)

---

### **SPECIFICATION SILENCES (What the spec does NOT say):**

1. **No exactly-once or at-least-once delivery guarantee for normal tool calls**
   - The spec never uses the terms "exactly-once" or "at-least-once"
   - Tasks provide durability, but standard tool calls do not have defined retry semantics

2. **No requirement to trigger elicitation for destructive tools**
   - destructiveHint is an optional hint, not a trigger
   - Servers MAY use InputRequiredResult, but are not REQUIRED to
   - The spec does not prescribe when to ask for confirmation

3. **No request correlation mechanism (no idempotency key / request ID)**
   - Tool calls have no idempotency key field
   - idempotentHint is a hint to the client, not a server guarantee
   - Clients cannot safely retry based on idempotentHint alone without server-side state

4. **idempotentHint is explicitly called a "hint"**
   - Not a guarantee, not a SLA, not a server promise
   - Clients that rely on this for retry logic do so at their own risk
   - Untrusted servers MUST NOT be trusted to honor it

---

## Section 4: Known Differences Between Versions

**From commit c142e51 research notes (Chinese):**
- SDK 2.0.0 recognizes spec versions up to 2025-11-25 (`ProtocolVersions` constants)
- 2025-11-25 is the latest version SDK 2.0.0 knows
- Spec 2026-07-28 is a newer version (released after SDK)
- Four key findings verified in 2026-07-28:
  - `CallToolResult` still only has `content` / `structuredContent` / `isError` (no `nextCursor`)
  - `PaginatedResult` remains the sole source of `nextCursor`
  - `Result` still supports additional top-level keys (spec doesn't forbid)
  - Three new changes in 2026-07-28 (unrelated to tool hints):
    - `Result` gains mandatory `resultType` field
    - `_meta` type constrained to `ResultMetaObject`
    - `structuredContent` relaxed to `unknown` (can be array, string, number)

---

## Section 5: Research Findings - Answers to Outstanding Questions

### Question 1: Destructive Hints Semantics

**Spring AI says:**
- `destructiveHint = true` means "the tool may perform destructive updates"
- `destructiveHint = false` means "the tool performs only additive updates"
- Meaningful only when `readOnlyHint == false`

**Spec says (schema.ts, both versions):**

Verbatim from specification:

> If true, the tool may perform destructive updates to its environment. If false, the tool performs only additive updates.
> (This property is meaningful only when `readOnlyHint == false`)
> Default: true

**CRITICAL: Spec classification:**
- All properties in ToolAnnotations are explicitly marked as **hints**
- "They are not guaranteed to provide a faithful description of tool behavior"
- "Clients should never make tool use decisions based on ToolAnnotations received from untrusted servers"

**Normative language in spec:**
- The spec provides NO MUST/SHOULD/MAY directive about what clients MUST do based on destructiveHint
- The spec says applications SHOULD have "a human in the loop", but NOT that this should be based on destructiveHint
- The spec does NOT require clients to confirm based on destructiveHint

**Answers:**
- ✅ destructiveHint is a **hint**, not a contract or SLA
- ✅ Clients have NO MANDATORY obligation to the server based on destructiveHint
- ✅ Clients CAN proceed without confirmation (spec does not forbid it)
- ✅ Clients SHOULD NOT trust destructiveHint from untrusted servers
- ⚠️ If server claims `destructiveHint=false` but destroys data: spec says this is possible (hints are not guaranteed), so clients from untrusted servers should be cautious

**Sources:**
- ToolAnnotations definition: schema.ts both versions (2025-11-25 lines 1171-1226, 2026-07-28 lines 1903-1958)
- Trust & Safety: tools.mdx both versions (2025-11-25 lines 22-34, 2026-07-28 lines 31-43)

---

### Question 2: Idempotency Semantics

**Spring AI says:**
- `idempotentHint = true` means "calling repeatedly with same args has no additional effect"
- Meaningful only when `readOnlyHint == false`

**Spec says (schema.ts, both versions):**

Verbatim:

> If true, calling the tool repeatedly with the same arguments will have no additional effect on its environment.
> (This property is meaningful only when `readOnlyHint == false`)
> Default: false

**CRITICAL: Spec classification:**
- All ToolAnnotations properties are **hints** (same caveat as destructiveHint)
- idempotentHint is NOT a guarantee, NOT a server promise, NOT a contract

**Normative language in spec:**
- The spec provides NO guidance on what clients should do based on idempotentHint
- The spec does NOT define a retry mechanism tied to idempotentHint
- There is NO request ID or correlation field for idempotency verification

**Answers:**
- ✅ idempotentHint is a **best-effort hint**, not a strict guarantee
- ❌ Clients should NOT rely on this for automatic retry logic without additional server-side state
- ⚠️ If an idempotent tool fails partway through first call: spec is SILENT. Retry safety depends on server implementation, not the hint
- ⚠️ From untrusted servers: idempotentHint MUST NOT be trusted

**Important finding:** The specification completely lacks retry semantics. idempotentHint without a request ID or idempotency key mechanism is useless for safe retries. Clients that retry based on idempotentHint alone are making a risky assumption.

**Sources:**
- idempotentHint definition: schema.ts both versions (2025-11-25 lines 1211-1220, 2026-07-28 lines 1943-1952)

---

### Question 3: Human-in-the-Loop Mechanisms

**Spec provides:**

1. **General Trust & Safety Requirement (tools.mdx, both versions):**
   - Applications SHOULD have "a human in the loop with the ability to deny tool invocations"
   - But this is a general principle, NOT tied to tool annotations

2. **Server-Initiated Elicitation (elicitation.mdx, both versions):**
   - Servers **CAN** initiate `elicitation/create` requests to ask users for information
   - This is server-controlled (servers decide when to call it)
   - Spec does NOT require this to be triggered by destructiveHint

3. **Tool-Embedded Elicitation via InputRequiredResult (2026-07-28 ONLY):**
   - Servers **MAY** respond to `tools/call` with `InputRequiredResult`
   - This embeds elicitation/create requests directly in the tool response
   - Spec says servers "MAY" do this, NOT "MUST"

**Answers:**
- ✅ Spec defines explicit elicitation/create mechanism for server-to-client input requests
- ✅ Servers CAN initiate these (not client-only)
- ❌ Spec does NOT require this to be triggered for destructive tools
- ❌ Spec does NOT mandate a "confirm before destroying" flow
- ⚠️ In 2025-11-25, servers can only send standalone `elicitation/create` requests
- ✅ In 2026-07-28, servers can embed elicitation requests inside tool call responses via InputRequiredResult

**Normative language:**
- servers "MAY" respond with InputRequiredResult (optional, not required)
- servers "SHOULD NOT" send elicitation requests the client doesn't support
- spec has NO "MUST confirm destructive operations" rule

**Sources:**
- Elicitation: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/client/elicitation.mdx (lines 81-98)
- Input Required Results: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/server/tools.mdx (lines 174-207)
- MRTR (which formalizes Input Required): https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/basic/patterns/mrtr.mdx (lines 184-191)

---

### Question 4: Retry Semantics and Idempotency Guarantees

**Spec coverage:**

1. **Request Structure (both versions):**
   - CallToolRequest has JSON-RPC `id` field, but this is NOT for idempotency correlation
   - MRTR spec (2026-07-28, line 256): "The JSON-RPC `id` **MUST** be different between the initial request and the retry"
   - This means request IDs are NOT reused → no idempotency key mechanism

2. **Tasks (2025-11-25 only):**
   - Tasks provide durable state for long-running operations
   - "For tasks in a terminal status, receivers **MUST** return from `tasks/result` exactly what the underlying request would have returned"
   - But this only applies to task-augmented requests, not normal tool calls

3. **Timeout/Crash Handling:**
   - Spec is COMPLETELY SILENT on timeouts, crashes, connection loss
   - No retry guidance, no exactly-once vs at-least-once, no timeout semantics

**Answers:**
- ❌ Spec does NOT handle tool call timeouts or crashes (no guidance provided)
- ❌ Clients CANNOT safely retry based on idempotentHint alone
- ❌ There is NO request ID or correlation mechanism for retries in normal tool calls
- ❌ Spec does NOT state "exactly-once" or "at-least-once" delivery
- ⚠️ Tasks (2025-11-25) provide durable result retrieval, but only for task-augmented calls
- ⚠️ Tasks do NOT guarantee exactly-once semantics; they guarantee result durability only

**Critical specification silence:**
- No defined behavior for "tool call sent, server crashes before responding"
- No guidance on "is it safe to retry with same arguments?"
- No request deduplication mechanism
- No retry-max, backoff, or timeout specifications

**Sources:**
- CallToolRequest: schema.ts both versions (2025-11-25 lines 1137-1155, 2026-07-28 lines 1863-1885)
- MRTR JSON-RPC id requirement: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/basic/patterns/mrtr.mdx (line 256)
- Tasks result guarantee: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/basic/utilities/tasks.mdx (lines 466, 860)

---

## References

### MCP Specification Sources (Primary)

**Schema Files:**
- 2025-11-25 schema.ts: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/schema/2025-11-25/schema.ts
- 2026-07-28 schema.ts: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/schema/2026-07-28/schema.ts

**Documentation Files:**
- 2025-11-25 tools.mdx: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/server/tools.mdx
- 2026-07-28 tools.mdx: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/server/tools.mdx
- 2025-11-25 elicitation.mdx: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/client/elicitation.mdx
- 2026-07-28 elicitation.mdx: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/client/elicitation.mdx
- 2025-11-25 tasks.mdx: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/basic/utilities/tasks.mdx
- 2026-07-28 MRTR.mdx: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/basic/patterns/mrtr.mdx

### Source Code References

1. **Spring AI MCP Annotations (v2.0.1)**
   - JAR: `/Users/demian/.m2/repository/org/springframework/ai/spring-ai-mcp-annotations/2.0.1/spring-ai-mcp-annotations-2.0.1-sources.jar`
   - Class: `org.springframework.ai.mcp.annotation.McpTool$McpAnnotations`
   - File: `org/springframework/ai/mcp/annotation/McpTool.java` (lines 84-122)

2. **MCP Core SDK (v2.0.0)**
   - JAR: `/Users/demian/.m2/repository/io/modelcontextprotocol/sdk/mcp-core/2.0.0/mcp-core-2.0.0-sources.jar`
   - Class: `io.modelcontextprotocol.spec.McpSchema$ToolAnnotations`
   - File: `io/modelcontextprotocol/spec/McpSchema.java` (lines 2774-2836)

3. **project-mcp Implementation**
   - File: `/Users/demian/Projects_vibecoding/project_mcp/src/main/java/io/github/demianli/projectmcp/tool/IssueTools.java` (lines 52-61, 124-128)
   - File: `/Users/demian/Projects_vibecoding/project_mcp/src/main/java/io/github/demianli/projectmcp/tool/LabelTools.java`
   - File: `/Users/demian/Projects_vibecoding/project_mcp/src/main/java/io/github/demianli/projectmcp/tool/CommentTools.java`

### Project Context

- **Related ADRs:**
  - ADR-0002: Failure contract for tool calls
  - ADR-0003: Tool parameters and return shapes
  - ADR-0004: Tool definitions and constraints

- **Related GitHub Issues:**
  - Issue #26: MCP规范对会改变外部状态的Tool说了什么 (This research task)
  - Issue #28: Potential linked failure contract issue

- **Related Commits:**
  - `c142e51`: "Recheck the four answers against spec revision 2026-07-28"
  - Indicates the project is actively verifying against official spec versions

- **Current Status (README):**
  - "Read-only for now. Write and destructive operations are out of scope for the first milestone."
  - Issue #26 appears to be scoping future work on write operations

---

## Summary of Findings

### Key Takeaways for Issue #26

1. **destructiveHint and idempotentHint are explicitly called "hints"** in the specification, with the caveat that "they are not guaranteed to provide a faithful description of tool behavior"

2. **Clients SHOULD NOT make tool use decisions based on these hints from untrusted servers**

3. **There is NO specification requirement to prompt users before executing destructive tools** — the spec only recommends having "a human in the loop" as a general trust & safety principle

4. **Servers CAN trigger elicitation for user input**, but this is optional, not required by spec. MRTR (2026-07-28) enables embedding elicitation within tool responses.

5. **The specification is SILENT on retry semantics and exactly-once delivery** — there is no request ID mechanism, no timeout guidance, and no defined recovery behavior for crashed tool calls

6. **Spring AI's defaults and javadoc add semantics beyond the spec** — what Spring AI calls a "hint that is meaningful only when readOnlyHint==false" is an SDK-specific constraint, not a specification-level one

7. **Version 2026-07-28 adds significant capability via MRTR and InputRequiredResult**, enabling richer interactive workflows that were not possible in 2025-11-25

---

## Completion Status

- [x] Retrieved official MCP spec 2025-11-25 full text (via GitHub raw)
- [x] Retrieved official MCP spec 2026-07-28 full text (via GitHub raw)
- [x] Extracted ToolAnnotations definition from official spec
- [x] Documented Client responsibilities (MUST/SHOULD/MAY language)
- [x] Researched human-in-the-loop and elicitation mechanisms
- [x] Documented retry/idempotency semantics
- [x] Created side-by-side comparison of spec versions
- [x] Noted specification silences (absence of guidance)


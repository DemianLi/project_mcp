# MCP 协议自己怎么分页，管不管得到 Tool 的调用结果

解决 [#20](https://github.com/DemianLi/project_mcp/issues/20)。纯事实记录，不含建议——该走哪条路是
[#22](https://github.com/DemianLi/project_mcp/issues/22) 的事。

**查证对象与版本**（凡本文引用，皆亲眼所见）：

| 来源 | 版本 / 出处 |
| --- | --- |
| 规范正文 | `modelcontextprotocol.io/specification/**2025-11-25**/` |
| 规范 schema | `modelcontextprotocol/modelcontextprotocol`，`schema/2025-11-25/schema.ts`（取自 `main`） |
| Java SDK | `io.modelcontextprotocol.sdk:mcp-core:**2.0.0**`（`mcp-core-2.0.0-sources.jar`），由 Spring AI 2.0.1 传递引入 |

SDK 2.0.0 的 `io.modelcontextprotocol.spec.ProtocolVersions` 里声明了三个版本常量：
`MCP_2025_03_26`、`MCP_2025_06_18`、`MCP_2025_11_25`。以最新的那个为准查证。

---

## 1. `CallToolResult` 上没有分页字段；`nextCursor` 只长在列表操作上

`schema.ts` 里，游标是靠继承分发的，而 `CallToolResult` 不在继承链上：

```ts
export interface PaginatedResult extends Result {
  /**
   * An opaque token representing the pagination position after the last returned result.
   * If present, there may be more results available.
   */
  nextCursor?: Cursor;
}
```

`extends PaginatedResult` 的**全部**五处：`ListResourcesResult`、`ListResourceTemplatesResult`、
`ListPromptsResult`、`ListToolsResult`、`ListTasksResult`。请求侧对称，`extends PaginatedRequest`
的也是对应五处。

`CallToolResult` 继承的是 `Result`，不是 `PaginatedResult`，三个字段：

```ts
export interface CallToolResult extends Result {
  content: ContentBlock[];
  structuredContent?: { [key: string]: unknown };
  isError?: boolean;
}
```

请求侧同样没有游标——`CallToolRequestParams` 只有 `name` 与 `arguments`。

**但有个不能略过的转折**：`Result` 是开放的。

```ts
export interface Result {
  _meta?: { [key: string]: unknown };
  [key: string]: unknown;
}
```

那条 index signature 意味着**规范并不禁止**在 tool 结果顶层多放一个键；规范正文也说
「The `result` **MAY** follow any JSON object structure.」所以准确的说法是：`nextCursor` 在 tool
结果上**没有被认可**，而不是**被禁止**。两者差别很大。

### 规范与 SDK 在这里对不上

Java SDK 2.0.0 的 `CallToolResult` 是一个 record，成分固定四个：

```java
public record CallToolResult(
    @JsonProperty("content") List<Content> content,
    @JsonProperty("isError") Boolean isError,
    @JsonProperty("structuredContent") Object structuredContent,
    @JsonProperty("_meta") Map<String, Object> meta) implements Result {
```

**规范的 index signature 在 Java 类型里没有对应物**。schema.ts 允许多加一个顶层键，SDK 的 record
不允许——除非塞进 `_meta`（见第 3 节）。这个落差本身就是一条发现：靠「规范没禁止」来放顶层游标，
在这套 SDK 上做不到。

SDK 里另有 `PaginatedRequest(cursor, _meta)` 与 `PaginatedResult(nextCursor)` 两个 record，但四个
列表结果类型各自内联声明了 `nextCursor`，并未继承它们——这两个 record 在 SDK 里近乎摆设。

---

## 2. 「一次调用回不完」这件事，规范没给任何指引

`server/tools` 全页搜不到 truncation、chunking、splitting、分多次调用之类的说法；`pagination`
一词只出现一次，指向 `tools/list`（「To discover available tools, clients send a `tools/list`
request. This operation supports pagination.」），与 `tools/call` 无关。**规范在这件事上是沉默的**,
既没说该怎么办、也没说交给 Server 自便。

距离这个问题最近的一条，是 tool 结果里的 resource link——但它讲的是「另取」而不是「分页」：

> A tool **MAY** return links to Resources, to provide additional context or data. In this
> case, the tool will return a URI that can be subscribed to or fetched by the client

形状是 `{"type": "resource_link", "uri": ..., "name": ..., "description": ..., "mimeType": ...}`。
附带一条限制：

> Resource links returned by tools are not guaranteed to appear in the results of a
> `resources/list` request.

规范没说 resource link 是用来解决体积问题的；上面那句「provide additional context or data」是它给出
的全部用途说明。

---

## 3. `_meta`：规范为它定了命名规则，没有为它划定用途白名单

规范正文（`basic/index`，General fields）原文：

> The `_meta` property/parameter is reserved by MCP to allow clients and servers to attach
> additional metadata to their interactions.
>
> Certain key names are reserved by MCP for protocol-level metadata, as specified below;
> implementations MUST NOT make assumptions about values at these keys.

命名规则是硬的，且**直接约束到这个 Server 能不能用**：

- key 分两段——可选的 **prefix** 与 **name**。
- prefix 若出现，必须是点号分隔的 label 串加一个斜线；label 以字母开头、以字母或数字结尾，中间可含
  连字符。规范 **SHOULD** 用反向 DNS（`com.example/`）。
- **第二个 label 是 `modelcontextprotocol` 或 `mcp` 的 prefix 一律保留给 MCP**——`io.modelcontextprotocol/`、
  `dev.mcp/`、`org.modelcontextprotocol.api/`、`com.mcp.tools/` 都被占了；但 `com.example.mcp/`
  不算保留，因为第二段是 `example`。
- name 段若非空，首尾必须是 `[a-z0-9A-Z]`，中间可含 `-`、`_`、`.` 与字母数字。

**规范没有一句话禁止实现方把自有数据放进 `_meta`**——它给的是保留前缀与命名格式，而不是用途白名单。
SDK 侧 `CallToolResult` 的 `_meta` 是 `Map<String, Object>`，物理上放得进任何东西；SDK 自己也在
`Request.progressToken()` 里从 `_meta` 取 `progressToken`，即协议层确实拿 `_meta` 装过实义数据。

SDK 对 `_meta` 的全部说明只有一句 javadoc，指回规范：

```java
/** Base interface for MCP objects that include optional metadata in the `_meta` field. */
public interface Meta {
    /** @see <a href=".../2025-06-18/basic/index#meta">Specification</a> for notes on _meta usage */
    Map<String, Object> meta();
}
```

---

## 4. 游标：opaque 是对 **Client** 的约束，Server 那侧几乎没被绑住

`schema.ts`：

```ts
/** An opaque token used to represent a cursor for pagination. */
export type Cursor = string;
```

规范正文（`server/utilities/pagination`）：

> Pagination in MCP uses an opaque cursor-based approach, instead of numbered pages.
>
> * The **cursor** is an opaque string token, representing a position in the result set
> * **Page size** is determined by the server, and clients **MUST NOT** assume a fixed page size

约束落点是**不对称**的。对 Client 是 MUST：

> Clients **MUST** treat cursors as opaque tokens:
> * Don't make assumptions about cursor format
> * Don't attempt to parse or modify cursors
> * Don't persist cursors across sessions

对 Server 只有 SHOULD，且没有一条限制编码方式：

> Servers **SHOULD**:
> * Provide stable cursors
> * Handle invalid cursors gracefully

**所以「Server 能不能直接拿页码当 cursor」的答案是：能。** 规范禁止的是 Client 去解析它,不是 Server
去构造它。规范自己给的两个例子恰好就是 base64 过的页码——`"eyJwYWdlIjogM30="` 解开是 `{"page": 3}`,
`"eyJwYWdlIjogMn0="` 解开是 `{"page": 2}`。

**失效语义**：没有过期机制，没有 TTL，没有版本化。只有两条——Server **SHOULD** 提供 stable cursor
并优雅处理无效游标；Client **MUST NOT** 跨 session 保存游标。无效游标的处理有明确规定：

> Invalid cursors **SHOULD** result in an error with code -32602 (Invalid params).

注意这是**协议层错误**，不是 `isError: true` 的 tool execution error。ADR-0002 立的失败契约与这条
是两套东西。

**分页操作的名单**（规范正文）：`resources/list`、`resources/templates/list`、`prompts/list`、
`tools/list`。`tools/call` 不在其中。

---

## 尚未确证

- **规范正文的分页名单与 schema.ts 对不上。** 正文列了四个操作，schema.ts 里 `extends PaginatedResult`
  的却有五个——多出 `ListTasksResult`（`tasks/list`，2025-11-25 引入的 task 机制）。我取的 schema.ts
  来自 `main` 分支下的 `schema/2025-11-25/` 目录，无法断定这是发布后的修订、还是正文页面漏更。
  **对本 repo 无影响**（`tasks/list` 是协议层列表，同样不是 tool 调用结果），但引用「只有四个操作
  支持分页」时该知道这个出入。
- **SDK 2.0.0 里没有 `ListTasksResult`**（全 jar 搜不到）。所以 SDK 2.0.0 覆盖的是 2025-11-25 的一个
  子集，还是 task 机制在 SDK 里另有安置，我没有查证。
- **`structuredContent` 能否承载游标没有单独查证。** 它的 schema 是开放对象
  （`{ [key: string]: unknown }`），物理上放得下；但 ADR-0002 之后本 Server 的 Tool 返回类型是
  `CallToolResult`、走 TEXT 模式，`structuredContent` 这条路是否还通着，属于本 repo 自己的问题,
  不是规范问题,超出本票边界。
- **未查证其他 MCP Client（Inspector 之外）实际如何对待 tool 结果里的非标准顶层键或 `_meta`。**
  规范允许不等于 Client 会显示或转达。这是实测题，不是文档题。

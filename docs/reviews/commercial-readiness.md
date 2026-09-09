# 商用就緒評審：project_mcp MCP Server 架構評估

**檔案位置決定**：本文件新建於 `docs/reviews/commercial-readiness.md`。該目錄不存在於專案中，為了組織評審和架構決策文件而創立，並與既有的 `docs/adr/` 並行。

**評審日期**：2026-09-08 | **評審物件**：Spring AI 2.0.1 + MCP Java SDK 2.0.0 | **預期商用狀態**：生產部署前的架構評估

---

## 前置澄清：「MCP 2.0」＝ 2026-07-28 修訂版

### 術語確認

MCP 規格沒有 1.0/2.0 這種語意化版本號，用的是日期修訂版。截至 2026-09-08，官方 repo 裡實際存在的修訂版如下（`docs/specification/` 與 `schema/` 兩個目錄內容一致）：

| 修訂版 | 備註 |
|---|---|
| 2024-11-05 | 初版 |
| 2025-03-26 | |
| 2025-06-18 | structured content、OAuth 基礎 |
| 2025-11-25 | |
| **2026-07-28** | **最新**，即口語所稱的「MCP v2 / MCP 2.0」 |
| draft | 開發中 |

> 來源：`GET /repos/modelcontextprotocol/modelcontextprotocol/contents/docs/specification`，2026-09-08 實查。

本評審以 **2026-07-28** 為基準。以下條目全部取自該修訂版的 changelog（[raw](https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/changelog.mdx)），與本專案相關者節錄：

**Major changes**

1. **移除 session**：拿掉 Streamable HTTP 的 `Mcp-Session-Id`；`tools/list` 等端點不再隨連線而異（SEP-2567）。
2. **無狀態化**：移除 `initialize` / `notifications/initialized` 交握。每個請求在 `_meta` 自帶 `io.modelcontextprotocol/protocolVersion` 與 `clientCapabilities`；版本不符回 `UnsupportedProtocolVersionError`（SEP-2575）。
3. **`server/discover`**：伺服器 **MUST** 實作，公告支援的協議版本、能力與身分（SEP-2575）。
4. **MRTR**：以 `InputRequiredResult`（`resultType: "input_required"`）取代所有伺服器發起的請求（`roots/list`、`sampling/createMessage`、`elicitation/create`）（SEP-2322）。
5. **`resultType` 成為必填**：所有 result 都要帶；普通結果為 `"complete"`（SEP-2322）。
6. **移除 `ping`、`logging/setLevel`、`notifications/roots/list_changed`**；log level 改為逐請求以 `_meta` 的 `io.modelcontextprotocol/logLevel` 指定。

**Deprecated**

- **Roots、Sampling、Logging 三項標記棄用**（SEP-2577）。官方建議的替代路徑中有一條與本專案直接相關：stdio 傳輸下改寫 `stderr`，或改用 OpenTelemetry。

### 先講結論：規格符合度目前卡在相依鏈，不是卡在這份程式碼

本專案 `pom.xml` 用 `spring-ai 2.0.1` → `spring-ai-starter-mcp-server` → **MCP Java SDK 2.0.0**。該 SDK 的 `ProtocolVersions` 常數只到 `2025-11-25`：

```java
String MCP_2024_11_05 = "2024-11-05";
String MCP_2025_03_26 = "2025-03-26";
String MCP_2025_06_18 = "2025-06-18";
String MCP_2025_11_25 = "2025-11-25";
```

> 來源：`~/.m2/repository/io/modelcontextprotocol/sdk/mcp-core/2.0.0/mcp-core-2.0.0-sources.jar` → `io/modelcontextprotocol/spec/ProtocolVersions.java`。這是本專案實際編譯所用的那份相依本身。

**所以「這個 Server 符不符合 MCP 2.0」今天的答案是：不符合，而且原因不在這個 repo。** 無狀態核心、`server/discover`、必填 `resultType`、MRTR——四項都不是這份程式碼寫不出來，是 SDK 尚未提供。本評審因此拆成兩問：**(a)** 等 SDK 跟上時，這個架構擋不擋路；**(b)** 撇開規格版本，這個架構本身離商用還差什麼。
---

## 1. 規格符合度（Specification Conformance）

### 1.1 協議初始化與功能宣告

**符合情況**：✓ 合規，但宣告與實作之間有一處落差（實測後修正，見本節末）

- `initialize` 請求與 `InitializeResult` 應答均遵循 2025-11-25 規格
- `protocolVersion` 協商結果為 `2025-11-25`（由 Spring AI 與 MCP SDK 自動協商）
- 五個 Tool 正確宣告 `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint` annotations
- 宣告的 capability 比實作的多，見下

**capability 宣告（實測，非推論）**

以 Inspector 與手寫 JSON-RPC 各驅動一次，`InitializeResult` 回的是：

```json
{"completions":{}, "logging":{}, "prompts":{"listChanged":true},
 "resources":{"subscribe":false,"listChanged":true}, "tools":{"listChanged":true}}
```

Spring AI 預設把五個 capability 全開，本專案當時沒有關掉任何一個。實作的只有 `tools`：

| capability | 宣告 | 實際 | 後果 |
|---|---|---|---|
| `tools` | ✓ | 五個 Tool | 相符 |
| `resources` | ✓ | `resources/list` → `[]`、`resources/templates/list` → `[]` | 清單空，但 Client 會認為這個 Server 有 Resource 面 |
| `prompts` | ✓ | `prompts/list` → `[]` | 同上 |
| `logging` | ✓ | `logging/setLevel` 回 `{}` 照收，但 `src/` 裡沒有任何一處呼叫 SDK 的 logging notification API | Client 可以合法開啟 MCP logging，然後一則 `notifications/message` 都收不到 |
| `completions` | ✓ | `completion/complete` 對不存在的 prompt 回 `-32602` | 沒有可補全的東西 |

**校訂註記（2026-09-09）**：本節原本寫「無 Resource 宣告，符合設計決策 (CONTEXT.md:73-79,
ADR-0004)」，並引 `IssueTools.java:51-97` 作為 `initialize` 的出處。兩者都要更正。ADR-0004 決定的是
**不提供任何 Resource**，那件事成立且實測相符（清單是空的）；但 capability **有**宣告，
「不宣告」與「宣告了但空的」對 Client 是兩件事。原句是讀原始碼推論出來的，這次跑起來才看到。
`IssueTools.java` 也不是 `initialize` 的出處——那段完全在 SDK 手上，本專案沒有一行程式碼參與交握。

**已修（`65b4d67`）**：`application.yml` 加了三行
`spring.ai.mcp.server.capabilities.{resource,prompt,completion}: false`，重跑 `initialize`
確認 wire 上只剩 `{"logging":{}, "tools":{"listChanged":true}}`，並由
`SdkBoundaryAcceptanceTest.onlyTheImplementedCapabilitiesAreDeclared` 釘住。

`logging` 留著：metadata 只暴露 completion／prompt／resource／tool 四個屬性，沒有 logging 的，
要關得靠 customizer bean 整組換掉 capability。為一格沒人問的宣告新增一個類別不划算，
所以它是「宣告了、不使用、已記載」，`application.yml` 的註解與上述測試都把這件事講明。

**[規格引用]** 2025-11-25 | `initialize` | [`docs/specification/2025-11-25/index.mdx`](https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/index.mdx)（注意：2026-07-28 已移除此交握，見前置澄清）

### 1.2 Annotations 的規格語義

**規格原文**（2026-07-28, `server/tools.mdx` L304-307，[raw](https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/server/tools.mdx)）：

> For trust & safety and security, clients **MUST** consider tool annotations to be untrusted unless they come from trusted servers.

請注意這條的**受詞是 client**。規格約束的是「客戶端不得信任遠端伺服器自報的 annotation」，並沒有禁止伺服器讀自己的 annotation。

**本專案的用法**（CONTEXT.md:90-104）：`readOnlyHint` 同時扮演三個角色——對外的聲明、`destructiveHint`/`idempotentHint` 是否有意義的開關（`CommentTools.java:202-204` 的註解引用了規格這條）、以及「寫入分區」的命名來源。ADR-0009 明確決定**不**在 Server 端建立寫入門禁，寫入權限由 `gh` 解析的登錄決定。

**評估**：✓ **不違反規格**。這裡讀 annotation 的是伺服器自己，讀的也是自己的宣告，不是把某個遠端的宣告當權限依據。`WritePartitionAcceptanceTest` 做的是「聲明與路由是否一致」的測試驗證，不是執行期閘門——兩者不該混為一談。

**唯一該留意的**：這條分區規則的權威來源是 annotation 欄位，而 annotation 是給人與模型看的宣告。若日後有人把 `readOnlyHint` 當成授權判斷（而非分類命名）往上疊功能，那一步才會踩到規格這條警告。目前沒有。
---

### 1.3 Content 與 structuredContent 形狀

**符合情況**：✓ 合規

**Tool Results** (ToolResults.java:55-96)：
- 成功時：單一 `text` content block 含 JSON (Envelope 或 URL)
- 失敗時：`text` + `structuredContent` 雙重格式
  - `structuredContent` 含 `remedy` (enum), `retryAfterSeconds` (optional), `message`, `stderr`
  - 規格允許 `structuredContent` 為任意 JSON object — [2026-07-28 `server/tools.mdx`](https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/server/tools.mdx)

**已知限制**：
- `_meta` 未使用 (ToolResults.java 註解 line 146 明確說明)
- Envelope 結構 (`items`, `count`, `truncated`) 是本專案設計，非 MCP 規格 — 符合規格「Tools 可返回任意 JSON」的原則

---

### 1.4 Error 與 isError 語義

**符合情況**：✓ 合規。但失敗契約的覆蓋範圍比 ADR-0002 宣稱的窄一塊，見下。

**ADR-0002 的原理**：
- 所有失敗發出 `isError: true` 而非 JSON-RPC protocol error
- 分類邏輯集中在 `GhCli.classify()` (GhCli.java:184)
- 進到 Tool 方法體之後的失敗不走 Spring AI 的預設錯誤處理 (ToolResults.java:28-32)

**規格遵循** [2025-11-25 `server/tools.mdx` §Error Handling](https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/server/tools.mdx)：
- Tool 調用失敗回傳 `CallToolResult` with `isError: true` ✓
- 允許 `structuredContent` 攜帶額外信息 ✓

**實測補充（2026-09-09）：契約有一個框架層的破口**

失敗契約管得到的範圍，是 Tool 方法體開始執行之後。方法體之前還有一段：Spring AI 會先拿
`inputSchema` 驗參數。這一關擋下來的失敗，不經過 `ToolResults`，也就不帶 Remedy。

手寫 JSON-RPC 送一個缺 `repo` 與 `number` 的 `get_issue`，回來的是：

```json
{"content":[{"type":"text","text":"Tool (get_issue) input validation failed: Validation failed: JSON schema validation errors: [: 未找到所需屬性“repo”, : 未找到所需屬性“number”]"}],
 "isError":true}
```

三件事要記著：

1. **沒有 `structuredContent`**——沒有 `remedy`，也沒有 `stderr` 欄位。ADR-0002 的
   「每一次失敗都帶著一個 Remedy」在這條路徑上不成立。
2. **訊息跟著 JVM 預設 locale 走**。上面那串是在 `zh_TW` 的機器上跑出來的；
   換一台 `LANG=ja_JP` 的機器，同一個錯誤會變成日文。這不是化妝品問題——
   模型最常犯的錯就是漏一個必填參數，而這條路徑正是它最常拿到的回覆。
3. **這不是寫錯造成的**，跟 `ToolResults` 註解裡警告的「`ToolFailure` 擲在 `attempt` 外面」
   是不同的東西。那個是可以靠紀律避免的；這個是框架的必經之路。

**後續（`65b4d67`，[ADR-0011](../adr/0011-the-failure-contract-begins-at-the-tool-method.md)）**：
本節初稿說「在 tool callback 層攔截，導進 `ToolResults.failure(...)`」——那是推論，而且**錯了**。
兩條路都建起來量過：

- `validateToolInputs(false)`（透過 `McpSyncServerCustomizer`）**更糟**。缺必填參數變成
  `java.lang.NullPointerException: Cannot invoke "java.lang.Number.intValue()"`，
  JVM 內部細節直接上 wire，`structuredContent` 一樣沒有。
- 自訂 `JsonSchemaValidator` 摸得到 `validation.errorMessage()`，所以能解掉 locale，
  但摸不到結果的形狀——結果是 `ToolInputValidator` 自己組的。

所以這不是本專案的設計失誤，是 SDK 的結構性邊界。ADR-0011 把界線定下來：
**失敗契約的範圍是 Tool 方法體**。現況由
`SdkBoundaryAcceptanceTest.aCallTheSchemaRejectsCarriesNoRemedy` 釘住——它斷言
`structuredContent` **是 null**，當絆線用：SDK 哪天長出縫，那支測試會紅。

---

### 1.5 對 2026-07-28 的缺口

下表的「本專案」一欄，絕大多數不是設計缺陷，而是 SDK 天花板（見前置澄清）。

| 2026-07-28 要求 | 規格強度 | 本專案 | 成因 |
|---|---|---|---|
| 無狀態核心：移除 `initialize` 交握，`_meta` 帶協議版本與能力 | MUST | 仍走 2025-11-25 的交握 | SDK 天花板 |
| `server/discover` | **MUST** | 未實作 | SDK 天花板 |
| 所有 result 必填 `resultType` | MUST | `ToolResults` 產生的 `CallToolResult` 無此欄位 | SDK 天花板 |
| MRTR（`InputRequiredResult`） | 取代舊機制 | 不適用——本專案五個 Tool 都不需要中途索取輸入 | 不受影響 |
| 移除 session／`Mcp-Session-Id` | MUST | 不適用——stdio 無 session header | 不受影響 |
| `tools/list` 回傳 `ttlMs`／`cacheScope` | MUST（`CacheableResult`） | 未提供 | SDK 天花板 |
| `tools/list` 順序穩定 | SHOULD | 未明確保證（依 Spring AI 掃描順序） | 可自行處理 |
| Logging 功能棄用，stdio 建議寫 `stderr` | Deprecated | 寫檔案（`logs/project-mcp.log`） | 見 §3.4 |
| 錯誤碼分區（`-32020`~`-32099` 保留給規格） | MUST | 不受影響——本專案所有失敗走 `isError: true`，不鑄造 JSON-RPC 錯誤碼（ADR-0002） | 不受影響 |

**要點**：「不受影響」那四列是這次評審裡最值得注意的結果。2026-07-28 的破壞性變更大多打在 session、伺服器發起請求、SSE 續傳這些機制上，而本專案**一個都沒用**。它的窄——五個純請求／回應的 Tool、失敗只走 `isError`、不發通知、不要 sampling／roots——讓它在這次改版裡幾乎沒有需要拆掉的東西。真正要補的是新增項（`server/discover`、`resultType`、`ttlMs`），而那些都在 SDK 手上。

## 2. 傳輸與部署

### 2.1 現狀：Stdio 單體

**現況**：
- 唯一傳輸：Stdio (JSON-RPC over stdin/stdout)
- 啟動方式：客戶端子進程 (MCP 標準模式)
- Logging：檔案 (`logs/project-mcp.log`), stdout 保留給 JSON-RPC (application.yml:1-20)

**架構約束**：
```
Client → spawns → Server (Stdio)
                     ↓
                   GhCli (ProcessBuilder)
                     ↓
                   `gh` binary (subprocess)
```

Stdio-only 意味著：
- 無法進行 HTTP 路由、負載平衡、或反向代理
- 每個客戶端獲得一個進程副本
- 無法跨進程共享連接池或認證狀態

[規格依據] 2025-11-25 不強制 HTTP，Stdio 完全合法。但生產部署通常需要 HTTP。

### 2.2 商用部署路徑

**要實現 Streamable HTTP (HTTP transport)，需要**：

1. **依賴升級**（目前被 SDK 阻擋）：
   - MCP Java SDK 2.0.0 中 `HttpServletStreamableServerTransportProvider` 存在 (CONTEXT.md:206)
   - Spring Boot 4.1.1 已包含 Spring Web (pom.xml 透過 spring-ai-model 依賴 Reactor/Messaging)
   
2. **應用層改動**：
   - 新增 HTTP 端點 (Spring MVC 或 WebFlux controller)
   - 保留現有 Stdio 支援或獨立構建
   - 例：`/mcp` 端點接收 JSON-RPC，回傳流式結果

3. **配置變更**：
   - `application.yml` 中啟用 web-application-type
   - HTTP 認證層 (見第 4 節)

**分層評估**：
- **Controller 層**：無改動需要，Spring AI 處理
- **Tool 層**（IssueTools, CommentTools, 等）：無改動需要，Tool 邏輯與傳輸獨立
- **GhCli 層**：無改動需要，子進程生成與管理不受傳輸影響
- **Envelope/Remedy 層**：無改動需要，格式傳輸獨立

**結論**：架構分層足夠清晰，**轉換為 HTTP 傳輸在技術上可行但需要構建時選擇**（Stdio vs. HTTP binary）。不是 blocker，但必須在商用前決定。

---

### 2.3 OAuth 2.1 Authorization：不是缺口

> **2026-09-09 更正。** 本節原標題是「OAuth 2.1 Authorization 層缺口」，並把「應支援
> OAuth 2.1 流程」當成規格要求。**那是誤讀，而且兩個 baseline 都誤讀。** 規格對 stdio
> 傳輸的說法不是「應該做而沒做」，是「**不應該做**」。

**規格怎麼說**（`2025-11-25` 與 `2026-07-28` 的 *Authorization* §Protocol Requirements
一字不差）：走 stdio 傳輸的實作 **SHOULD NOT** 遵循 authorization 那份規格，而應該從環境
取得憑證。原文在 `docs/specification/2025-11-25/basic/authorization.mdx`，2026-07-28 的
對應檔在 `basic/authorization/index.mdx`。

整份 authorization 規格的適用範圍在它自己的 Purpose and Scope 就寫明是 HTTP-based
transports。一個 stdio Server 若真的在協議上收令牌並驗證，那是**偏離**規格而不是更貼近它。

**本專案現狀**：認證完全外包給 `gh`，不持有也不驗證任何令牌，憑證從環境取得。**這正是
規格對 stdio 指定的做法**，符合度是 ✓ 而不是缺口。

**仍然成立的部署問題**（這些與 OAuth 無關，是「一個進程一個身份」的後果）：
- 誰負責設定 `gh` 的認證？部署者。見 `docs/deploying.md`。
- 多租戶如何隔離？無法——一個進程一個登錄，見 §4.1／§4.2。
- 令牌輪換？**取決於憑證放在哪**：放環境變數要重啟 Server 進程（子行程繼承的是啟動時
  固定的那份環境），放 `gh` 的設定檔則下一次呼叫就生效。這件事只有讀原始碼才看得出來，
  已寫進 `docs/deploying.md`。

**結論**：以 stdio 為前提，這一項不是缺口。**若哪天加上 Streamable HTTP，整份
authorization 規格就一起進來，而那時是零實作。**

---

## 3. 可觀測性與維運

> **2026-09-09 全節重寫。** 原文的三項建議有兩項在這個 classpath 上做不出來，一項的歸因是
> 錯的，見本節末的校訂註記。以下是實作後的現況，全部由驅動 jar 量出來而非讀原始碼推出來。

### 3.1 現況

**每次呼叫一行**（`ToolResults.attempt`，ADR-0013）：

```json
{"@timestamp":"2026-09-09T11:03:24.965587Z",
 "log":{"level":"INFO","logger":"io.github.demianli.projectmcp.tool.ToolResults"},
 "process":{"pid":34178,"thread":{"name":"boundedElastic-1"}},
 "service":{"name":"project-mcp","version":"0.1.0-SNAPSHOT"},
 "message":"get_issue ok","callId":"c3dc7fc4","tool":"get_issue",
 "repo":"DemianLi/project-mcp-sandbox","outcome":"ok",
 "durationMs":"9","resultBytes":"254","ecs":{"version":"8.11"}}
```

- **格式**：Elastic Common Schema，Spring Boot 4.1.1 內建（`logging.structured.format.file: ecs`），
  沒有引入任何新依賴。
- **欄位**：`tool` `callId` `repo` `outcome` `durationMs` 恆有；`resultBytes` 只在成功時，
  `remedy` 只在失敗時。
- **寫入額外一行**：`add_issue_comment` 成功後多一行 `comment written`，帶 `commentUrl`
  永久連結。這結掉了 ADR-0007 掛著的那條 "a successful write leaves nothing in this
  Server's log"。
- **`GhCli` 的 argv 行仍在**，且自動帶上同一個 `callId` / `tool` / `repo`——MDC 在 lambda
  外圍設好，`GhCli` 本身沒有被改動任何一行來配合。
- **保留策略**：單檔 10MB、7 天、總量上限 100MB。上限這項不是預設值，Boot 的預設是無上限。

**stdout 保留政策**：console appender `OFF`，實測 stdout 只有 JSON-RPC。

### 3.2 一條紅線，以及它本來是破的

日誌記**形狀**不記**內容**：哪個 Tool、哪個 repo、多久、多大、怎麼結束會進去；issue 標題、
issue body、comment 內文、label 名稱不會。CONTEXT.md 已收錄這組詞。

**這條線在寫測試之前就是破的。** `GhCli` 記的是逐字 argv，而 `add_issue_comment` 的 argv
結尾是 `-f body=<整則留言>`。要失敗落在 **mutation** 而不是前面那次 lookup 才看得到，所以
五個 Tool 的既有測試從來沒撞到。現在 `GhCli.argv()` 會把 `CONTENT_VARIABLES`（目前一個：
`body`）的值換成長度：

```
-f body=<36 chars>` failed [UNKNOWN]: gh: mutation refused
```

`TraceContractAcceptanceTest` 把這件事釘在 wire 上，並且經過反向驗證——把修補拿掉，測試會紅。

**這條線沒蓋到的地方**：`GhCli` 會逐字記 `gh` 的 stderr，那是 GitHub 的文字不是這個 Server
寫的。若 GitHub 哪天用「把 body 引述回來」的方式拒絕一次寫入，內容會從這條路徑進到檔案。
不遮蔽 stderr 是刻意的——遮掉就等於讓失敗沒有任何證據。列為 ADR-0013 的具名限制。

### 3.3 缺口

| 需求 | 現狀 | 備註 |
|------|------|------|
| **結構化日誌** | ✓ | ECS JSON，Boot 內建 |
| **請求日誌** | ✓ | 每次呼叫一行，成功失敗皆有 |
| **Correlation** | ✓（進程內） | 自生 `callId`。**跨不過 `gh` 子行程**，也拿不到 MCP request id |
| **Metrics** | ❌ | 知情不做，ADR-0014 記名了誰該補 |
| **健康檢查端點** | ❌ | stdio 沒有端點可加；HTTP 傳輸後才有意義 |
| **日誌送出** | ❌ | 檔案就是終點，沒有 agent、沒有 exporter |

**兩件反直覺的事**：

1. **被 schema 擋掉的呼叫完全沒有日誌。** SDK 在 dispatch 之前驗證（ADR-0011），自己造
   result 就回去了，根本沒進 `ToolResults`。一個一直送壞請求的 Client，會產生一份看起來
   「這台 Server 很閒」的日誌。`durationMs` 量的也因此是 Tool 方法體而非整個請求。
2. **`durationMs` / `resultBytes` 是 JSON 字串不是數字**，MDC 只有字串型別。`jq` 要
   `tonumber`。要真數字得寫 `StructuredLoggingJsonMembersCustomizer`，判斷不划算。

### 3.4 校訂註記（2026-09-09）

原文這一節有三處要更正，兩處是我讀原始碼推出來而沒有跑過：

- **「無 request ID」歸因錯誤。** 原文列為未實作。實情是**拿不到**：`McpSyncServerExchange`
  只暴露 `sessionId()`，整個 SDK 的 `server` package 裡 grep `requestId` 零命中。所以
  correlation 只能自生，這是 SDK 的邊界不是這個專案的疏漏。
- **「Micrometer：`@Timed` 註解或 `MeterRegistry` 注入」照字面做不出來。** `micrometer-core`
  確實在 classpath 上（Spring AI 帶進來），但沒有任何 `micrometer-registry-*`，也沒有
  `spring-boot-starter-actuator`——`MeterRegistry` 是 actuator 自動配置的，沒有 bean 可注入。
  這句建議是讀依賴樹讀出來的，不是試出來的。見 ADR-0014。
- **「引入 Logstash JSON appender」是多餘的依賴。** Boot 4.1.1 內建 ECS / Logstash / GELF
  三種 formatter 與 logback `StructuredLogEncoder`，零新依賴。

---

## 4. 安全

### 4.1 認證與子進程隔離

**現狀**：

```
Server (one process)
    ↓
GhCli.run() / GhCli.runWrite()
    ↓
ProcessBuilder → `gh` binary (環境變數 + PATH 查詢)
    ↓
gh 解析 GH_TOKEN 或 git config 中的登錄狀態
```

**一個進程 = 一個登錄身份**。每次調用 `gh` 都用同一個登錄。

### 4.2 多租戶場景的資訊洩露風險

**情景**：SaaS 部署，多個用戶共享一個 Server 進程

**風險**：
- 用戶 A 的登錄狀態對用戶 B 可見
- 用戶 A 看到用戶 B 有權訪問的倉庫 (不同的 owner/repo 對應不同的權限)
- 若 `gh` 緩存或環境變數沒有正確隔離，令牌可能洩露

**ADR-0009 的局限性**：
- ADR 決定不在 Server 端建立讀寫門禁
- 依賴部署者「為每個租戶啟動一個 Server 副本」
- 若強制單進程，無隔離手段

**[規格依據]** — **2026-09-09 更正，原文引錯了。** 原本寫「2025-11-25 Security Best
Practices：伺服器應驗證客戶端身份（通常經由 OAuth）」。2025-11-25 底下**沒有**這份文件，
也**沒有**這條要求：`docs/specification/2025-11-25/basic/` 只有 authorization、index、
lifecycle、transports 四份，安全條款在 `index.mdx` 的 §Security and Trust & Safety，而且
「取得明確同意」那幾條的對象是 **Host** 不是 Server。這一項是被發明出來的規格要求。

實際存在、而且對本專案生效的是 `index.mdx` §Implementation Guidelines 那五條 **SHOULD**，
其中第二條要求實作者提供關於安全影響的清楚文件——這正是 `docs/deploying.md` 存在的理由，也是這一節原本結語「部署文件缺」唯一站得住的
部分。該缺口已補。**「一個進程一個身份」的風險本身完全不受影響**，它來自 ADR-0009 與這個
Server 的形狀，不需要一條規格來背書。

### 4.3 Prompt Injection 與 GitHub 內容

**向量**：
- Tool 參數来自客戶端（LLM agent）
- `owner`, `repo`, `body`, `cursor` 等被傳遞給 `gh api graphql` 或 `gh issue comment`
- `gh` 的 `-f` 以字面字串送出變數，不經 shell；`-F` 的魔法讀法（JSON 純量、`{owner}` 代換、`@path`／`@-` 讀檔）只用在 `int` 參數上（`CommentTools.java:27-42` 的 javadoc 完整記錄了這三種讀法與實測）

**已有保護** ✓：
- 變數依型別選 flag：字串走 `-f`（字面值），`int` 走 `-F`。規則不是「避開 `-F`」——`CommentTools.java:177,183` 的 `-F number=`／`-F last=` 是刻意且正確的，因為 `int` 帶不了 `-F` 的三種魔法讀法
- 無 shell 調用 (ProcessBuilder — GhCli.java:141-146)

**剩餘風險**：
- GitHub Issue **內容**（body, comments）可能含惡意 markdown 或 HTML
- 若 Client 直接渲染 Tool 回傳內容，可能產生 XSS
- 本 Server 不負責渲染，但應在文檔中警示

**評估**：✓ Server 層安全。Client 層責任。

> **2026-09-09 更新：上面那個評估太早了。「不經 shell」是對的，但參數不是只有 shell 一條路。**
>
> 寫 `docs/measurements/gh-compatibility.sh` 時撞到的：`owner` 沒有任何檢查就參與組成
> `--repo owner/repo`，而 **`gh` 的 `--repo` 接受的是 `[HOST/]OWNER/REPO`**。所以 `owner`
> 裡放一個斜線，第一段就變成**主機名**。
>
> 實測，從 Tool 介面進去，不是直接打 `gh`：
>
> | 送進去的 `owner` / `repo` | Server 實際去連的地方 | Client 收到的 Remedy |
> |---|---|---|
> | `a/b` ／ `c` | `https://a/api/graphql` | `UNKNOWN` |
> | `127.0.0.1:8099/a` ／ `b` | `https://127.0.0.1:8099/api/graphql` | **`RETRY`** |
>
> **兩個問題，第二個比第一個嚴重。**
>
> 一是**外連目的地由呼叫方決定**。這個 Server 不渲染內容、不執行 shell，但它會照著參數
> 去連一台第三方指定的主機——而參數的來源，在 MCP 的部署形狀裡，經常是讀了 GitHub issue
> 內容之後的模型。**未驗證**的是那個請求裡帶不帶憑證：`gh` 文件說 `GH_TOKEN` 是給
> github.com 的、其他主機走 `GH_ENTERPRISE_TOKEN`，所以推測不帶，但這裡沒有量到——要量
> 得架一台憑證被信任的 TLS 監聽器，那件事沒做。
>
> 二是**它被分類成 `RETRY`**：「GitHub could not be reached. The network looks
> unavailable.」網路好得很，是位址被寫壞了，而 Client 被告知的動作是「再試一次」。這正是
> ADR-0002 自己列為最糟的那一類——**有信心的錯誤 Remedy**，比 `UNKNOWN` 更壞，因為
> `UNKNOWN` 至少會把 stderr 原文交出去讓人自己看。
>
> 順帶一提，那段 stderr 是 `network` 那一列第一次拿到**真的來自 `gh`** 的樣本：
> `Post "https://127.0.0.1:8099/api/graphql": dial tcp 127.0.0.1:8099: connect: connection refused`。
> 該列現在三個樣本全是 `UNMEASURED`。
>
> **還沒修。** 修的形狀大概是 Tool 層在呼叫前拒絕帶斜線（或空白）的 `owner`／`repo`，
> 像 `add_issue_comment` 拒絕空白 body 那樣自己發明一個失敗——但那會動到失敗契約，
> 該走一張 wayfinder 票而不是順手塞進這次的量測工作。

### 4.4 Timeout 與資源耗盡

**GhCli.TIMEOUT_SECONDS = 30** (GhCli.java:64)

> 這一節談的是**資源耗盡**那一半。逾時本身（30 秒的由來、Client 的時鐘與本 Server 的
> 預算是兩回事、取消通知沒人實作）收在 §7.2 P5 與 `docs/deploying.md` 的 *Two clocks*。

**檢查項**：
- ProcessBuilder 有無資源上限? (GhCli.java:146 無 `redirectErrorStream()` 或資源配置)
- 若 `gh` 回傳極大輸出，是否會 OOM?

> **2026-09-09 更新：量過了，方向對、比重錯，而且下面有更嚴重的東西。**

**已實作的上限**：`GhCli.MAX_RESPONSE_BYTES = 8 MB`。超過就以 `FIX_REQUEST` 拒絕，量在
`readAllBytes` 之後、轉成 `String` 之前——那是位元組開始被放大的地方。見
[ADR-0015](../adr/0015-a-ceiling-on-one-response.md)。

**實測數字**（`get_issue`，256 MB heap）：

| 回應大小 | 修改前 |
|---|---|
| 1 / 10 / 20 / 40 MB | 整包通過並送達 Client |
| 60 MB | `OutOfMemoryError` |

斷點約在 **heap 的五分之一**，因為 bytes → `String` → tree → record → 再序列化，每一步各持
一份。而 GitHub 自己形狀撐死是 **6.57 MB**（100 則留言各 65,536 字，實測 64 ms 回來、判為
成功）。**所以 OOM 不是日常傷害；日常傷害是 6.5 MB 一次進模型的 context 而沒人有意見。**

**真正嚴重的是 OOM 的壞法**：Client 收到的不是 `isError`、不是 Remedy、不是協議錯誤，而是
**什麼都沒有**；日誌裡除了 Reactor 的堆疊什麼都沒留；而且**行程在 stdin 關閉之後還活著**
——實測十分鐘還握著 pipe，最後是手動 kill 掉的。stdio 下 Client 放棄後會重啟一個新的，舊的
就留在機器上。那正是 `GhCli.kill(Process)` javadoc 說這個類別存在就是為了避免的失敗，換一扇
門進來。

**已修**：`ToolResults` 多一條 `catch (Error)` 分支，寫下 `outcome: "fatal"` 的痕跡行然後
`halt` 掉行程；部署層再加一對 JVM 旗標。**旗標必須成對**——`-XX:+ExitOnOutOfMemoryError`
單獨用會把 `Terminating due to…` 印到 **stdout**，也就是 JSON-RPC 串流本身。三種組合都實測
過，只有配上 `-XX:+DisplayVMOutputToStderr` 才既會死又不弄髒協議。

**原建議的兩條都不採用**：「記錄超大回應」是把問題寫進日誌而不是解決它；
`redirectErrorStream(false)` 則是無操作——`GhCli` 從未呼叫過 `redirectErrorStream`（已 grep
確認），而 `ProcessBuilder` 的預設本來就是分離（JDK API 文件所載，非本次實測），兩條 pipe
也已經被併發抽乾。

### 4.5 限流：規格的 MUST，本專案沒有

2025-11-25 `server/tools.mdx` 的 Security Considerations 是四條並列的 MUST，其中
`Rate limit tool invocations` 本專案完全沒有。**這是真正的不符合規格**，不是解讀差異。

要留意的陷阱：`gh` 撞到 GitHub 限流、`GhStderr` 判成 `RETRY` 並帶上等待秒數——
那是下游限流被動反映回來，不是這個 Server 在限流。讀成合規就是讀反方向。

已由 [ADR-0012](../adr/0012-no-rate-limiting-and-why.md) 記為**知情的偏離**並指定了補的人：
單機以外的部署要自己加，位置在 Tool 層前面而不是 `GhCli`——`GhCli` 數的是子進程啟動次數，
規格講的是 Tool invocation。

---

## 5. 架構可擴充性

### 5.1 第六個 Tool 的邊際成本

**新增一個 Tool 需要**：

1. **Java 類**：新增 @McpTool 方法 (若邏輯不與現有工具共享) 或在現有組件中添加
   - 約 50-200 行代碼 (含參數驗證、文檔)

2. **GhCli 集成**：調用 `GhCli.run()` 或 `GhCli.runWrite()`
   - 無新的 GhCli 改動必要（已通用）

3. **Failure handling**：自動，`ToolResults.attempt()` 已統一處理 (ToolResults.java:55-61)

4. **GhStderr.classify()**：若 `gh` 有新的失敗訊息，擴展 switch/case
   - ADR-0002 已預留此點 (line 162: "verbatim stderr always travels alongside")

5. **Test 覆蓋**：
   - Wire 層 acceptance test (tool 與 `gh` 真實交互)
   - Coverage 層 (mock `gh`, 測試邏輯)

**成本評估**：
- 如果 Tool 讀取 GraphQL (模仿 list_issue_comments): ~150 LOC + 查詢文檔
- 如果 Tool 讀取 REST (簡單): ~100 LOC
- 如果 Tool 寫入 (模仿 add_issue_comment): ~200 LOC + 原子性測試

**瓶頸分析**：

| 階段 | 瓶頸 | 影響 |
|------|------|------|
| **Tool 層** | 參數設計、return shape | 已有 ADR 範本 (ADR-0001 等) |
| **GhCli** | `classify()` 分支 | 新失敗訊息 → 新 branch，OK |
| **Failure contract** | Remedy 列舉 | 已涵蓋五類 (RETRY, FIX_REQUEST, ASK_OPERATOR, CHECK_BEFORE_RETRY, UNKNOWN) |
| **Envelope** | 結構固定 | 新 Tool 若返回列表，沿用同樣 items/count/truncated 結構 |

**結論**：✓ 邊際成本低。主要工作是 Tool 邏輯設計，不是架構改動。

### 5.2 GhCli 作為單一出口的優勢與風險

**優勢** ✓：
- 所有 Tool 共享同一個 failure contract (GhCli:26-30)
- 執行、超時、子進程生命週期集中管理
- 新 Tool 自動繼承成熟的錯誤分類與恢復提示

**風險** ⚠️：
- 若 `gh` 升級改變了 stderr 訊息格式，GhStderr.classify() 失配
  - 已知問題，ADR-0002 Consequences line 161 明確記檄
  - 設計上接受：未匹配的 stderr 落入 UNKNOWN，並完整記錄
  
- Tool 不能自訂 timeout (都是 30 秒)
  - 可接受，但理由不是原本寫的那個。**「GitHub API 有速率限制」跟逾時預算無關**——
    速率限制回的是一個分類得出來的失敗（`RETRY`），不是一段等待。
  - 真正的理由量出來了：常態 542–966 ms，30 秒是它的三十倍以上（§7.2 P5）
  - 一次 Tool 呼叫不等於一次 `gh`：`add_issue_comment` 打兩次，最壞 ~60 秒
  - 若某 Tool 需不同 timeout，可在 GhCli 中設定，不涉及 Tool 層改動

**評估**：不是瓶頸。是堅固的設計。

### 5.3 Failure Contract 的擴散

**當前狀態**：

- 五個 Remedy 常數 (Remedy.java)
- GhStderr 內 switch 語句 (~30 branch，對應 gh 已知失敗)
- Tool 層無分支邏輯 (ToolResults.attempt 統一處理)

**未來擴展**：
- 第二個寫入 Tool (e.g. `create_issue`) 會否引入新 Remedy?
  - 不太可能。CREATE 的失敗（無權限、倉庫滿、配額）已被現有 5 類涵蓋

- 若加入不用 `gh` 的 Tool (e.g. 直接 REST client)?
  - 需要新的 failure type 或新的 classify 層
  - 這是架構決策，ADR-0002 當時沒考慮，但可擴展（新建 RestFailure.classify()）

**結論**：Contract 緊湊，應對當前 Tool 集無壓力。如加入非 `gh` 的 Tool，需新 ADR。

### 5.4 可測試性

**測試分層** (CONTEXT.md:169-181)：

- **Acceptance layer** (`wire/` 目錄)：
  - 橫跨 Server JSON-RPC 邊界
  - 真實 `gh` 或 mock `gh` binary
  - 每個 Tool 都有一份 (e.g. `listToolsAcceptanceTest`, `WritePartitionAcceptanceTest`)

- **Coverage layer** (`gh/`, `tool/` 目錄)：
  - 單元測試，不涉及 wire
  - Mock GhCli, Mapper 等

**易於擴展**：
- 新 Tool 添加 acceptance test (呼叫 Tool, 驗證回傳)
- 新 `classify()` branch 添加 GhStderr 單元測試
- 無需改動測試框架

---

## 6. 版本與依賴成熟度

### 6.1 Java 25

**Java 25 是 LTS**，不是短期版本。

| 項目 | 事實 | 來源 |
|---|---|---|
| GA | 2025-09-16 | [OpenJDK JDK 25](https://openjdk.org/projects/jdk/25/) |
| LTS 狀態 | **25 (LTS)**；21 (LTS)、22–24 (non-LTS)、26 (non-LTS)、27 (non-LTS) | [Oracle Java SE Support Roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) |
| Premier Support | 至 2030-09 | 同上 |
| Extended Support | 至 2033-09 | 同上 |

**評估**：✓ 這是這個技術棧裡**風險最低**的一條腿。無需更動。

> 校訂註記：本文件初稿曾把 Java 25 列為 blocker，稱其為「2024 年 9 月的短期版本、3 個月後停止支援」，並建議降級到「Java 23 (LTS)」。三項皆誤——日期錯一年、LTS 狀態相反、且 Java 23 並非 LTS。該 blocker 已刪除。

### 6.2 Spring Boot 4.1.1 / Spring AI 2.0.1

這才是相依鏈上真正值得留意的地方——**兩者都是三週前才發佈的版本**。

| 套件 | 版本 | 發佈日期 | 距今 |
|---|---|---|---|
| Spring Boot | 4.1.1 | 2026-08-20 | 約 3 週 |
| Spring AI | 2.0.1 | 2026-08-21 | 約 3 週 |

> 來源：GitHub Releases API，`spring-projects/spring-boot` tag `v4.1.1`、`spring-projects/spring-ai` tag `v2.0.1`，2026-09-08 查詢。

**商用含意**：這不是「不能用」，而是「還沒有人替你踩過雷」。教材用途完全沒問題；若要商用，值得明確記錄一條相依版本策略（要不要釘住、多久追一次、出事往哪個版本退）。目前 repo 裡沒有這樣的記載。

**未查證**：這兩個版本各自的支援終止日期未能從一手來源取得，本文不做斷言。

### 6.3 MCP Java SDK 2.0.0

- 支援協議版本上限 **2025-11-25**（來源見前置澄清）。
- **官方時程已公布**：Java SDK **3.x** 將實作 2026-07-28 修訂版，含 `server/discover` 與 SEP-2575 無狀態生命週期；首個 3.0.0 milestone 版本規劃於 **2026 年 9 月**。
  > 來源：[`modelcontextprotocol/java-sdk` ROADMAP.md §3.x](https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/main/ROADMAP.md)，2026-09-08 查詢。
- 同份文件載明 Java SDK 為官方 **Tier 2 SDK**，承諾在新修訂版發佈後六個月內跟上。
- **含意**：Blocker #1 有明確的解除路徑，而且就在這個月。本專案要做的不是繞過它，是等 3.x 出來後升版並補上新增項——`server/discover`、`resultType`、`ttlMs`/`cacheScope`。這三項都落在 Spring AI／SDK 層，本專案的 Tool 層與 `GhCli` 層預期不需改動。

## 7. 商用就緒等級評定

### 7.1 Blocker（阻止商用的項目）

#### Blocker #1：規格版本天花板在相依鏈上
- **問題**：目標若是「符合 2026-07-28（MCP 2.0）」，今天做不到。`server/discover`（MUST）、必填 `resultType`、無狀態核心都缺。
- **根本原因**：MCP Java SDK 2.0.0 上限為 2025-11-25，非本 repo 的架構問題。
- **商用影響**：若客戶要求 2026-07-28 相容，此為硬性阻擋。
- **修復成本**：不在本專案手上。SDK 3.x 規劃於 2026 年 9 月推出（見 §6.3），屆時升版即可；急用則須改用其他語言的 SDK。

#### Blocker #2：Stdio-Only 傳輸
- **問題**：無 HTTP，無法反向代理、負載平衡、水平擴充。
- **相關**：2026-07-28 的無狀態化正是為了讓 Server 能跑在 Cloudflare Workers 這類無狀態基礎設施上；stdio 單體拿不到這個好處。
- **商用影響**：單機／單租戶內網部署可接受；分散式部署必須先過這關。
- **修復成本**：中。§2.2 的分層分析顯示 Tool 層與 GhCli 層都不需改動。

#### Blocker #3：多租戶隔離缺失
- **問題**：一個進程 = 一個 `gh` 登錄。多租戶 SaaS 無法隔離用戶認證狀態。
- **根本原因**：ADR-0009 的架構決策（認證外包給 `gh`）。對單租戶是優點，對多租戶是硬牆。
- **商用影響**：目標若是多租戶 SaaS，必須改為每租戶一進程，或重新設計認證層。
- **修復成本**：高。

### 7.2 上線前應補項目（Pre-Launch）

#### ~~P1: 結構化日誌~~ → 已完成（2026-09-09，ADR-0013）
- **做法**：`logging.structured.format.file: ecs`，Spring Boot 4.1.1 內建，**沒有**引入
  logback JSON appender——原建議的那個依賴是多餘的。
- **順帶做掉的**：每次呼叫留一行痕跡（原本只有失敗才留），寫入成功多一行帶永久連結，
  開機三行框架噪音壓掉，保留策略明寫並加上總量上限。
- **順帶修掉的一個外洩**：`add_issue_comment` 的 mutation 失敗時，整則留言內容會被寫進
  日誌檔。見 §3.2。

#### ~~P2: OAuth 2.1 整合文檔~~ → 已完成（2026-09-09，`docs/deploying.md`）
- **順帶更正**：本項原本的前提是「缺 OAuth」。查了規格原文後，stdio 傳輸下規格說的是
  **SHOULD NOT** 走 authorization 那套、憑證從環境取得——所以那不是缺口。見 §2.3 的更正。
- **沒有給 Dockerfile**，是刻意的：這台機器上 docker daemon 沒開，沒 build 過也沒跑過的
  範例，價值低於它必須滿足的條件清單，而條件清單對 Dockerfile、systemd unit 與裸
  `java -jar` 都適用。要 Dockerfile 的話，把 daemon 開起來就補。
- **文件裡三件只有讀原始碼才知道的事**：令牌放環境變數要重啟才換得掉（`ProcessBuilder`
  繼承的是啟動時固定的環境）；`gh` 必須在 PATH 上而且沒有 property 可以改；`gh` 寫到
  stderr 的升級通知會在失敗時一起進到分類器與模型的視野。

#### ~~P3: 監控指標 (Metrics)~~ → 已收成知情決策，見 ADR-0014
- **現狀**：不做，且理由記名了。stdio 一桌一 process，計數器活不過那個 process，沒有端點
  可 scrape 也沒有對端可 push；而且這個 classpath 上沒有 `MeterRegistry` bean（沒有
  actuator），原本的建議寫法根本編不出來。
- **替代**：要的數字都在日誌裡，只是逐筆而非聚合——`durationMs` 是延遲，`outcome` /
  `remedy` 是失敗率與其分類，一行就是一次呼叫。聚合是一條 `jq`。
- **誰該補**：把這個 Server 部署到一張桌子以外、傳輸換成 HTTP 的人。見 ADR-0014。

#### ~~P4: 超大回應處理~~ → 已完成（2026-09-09，ADR-0015）
- **做了什麼**：8 MB 上限（`GhCli`，原始位元組）＋ 致命 `Error` 時寫痕跡行並終止行程
  ＋ 一對 JVM 旗標。兩個邊界都有 wire 層測試釘住，其中一支會**真的把 Server 跑到 OOM**。
- **原建議兩條都沒採用**：上限不放 Tool 層而放 `GhCli`（放大鏈之前，Tool 層已經付掉峰值）；
  Remedy 不用 `UNKNOWN` 而用 `FIX_REQUEST`（`UNKNOWN` 的語義是「認不出這個失敗」，而這是
  一個我們自己發明、命名、還數得出位元組的失敗）。
- **量出來才知道的比重**：GitHub 形狀撐死 6.57 MB，OOM 斷點在 heap 的五分之一——所以
  日常傷害不是 OOM，是 6.5 MB 進 context。詳見 §4.4。

#### ~~P5: Timeout 政策文檔~~ → 已完成（2026-09-09，`docs/deploying.md` §Two clocks、ADR-0016）

> **原提法漏了一半。** 「為何選 30 秒」只是其中一個時鐘。寫下去才發現規格的 Timeouts 條款
> 綁的是**送出請求的一方**（`basic/lifecycle.mdx`），本 Server 在 `tools/call` 上是接收方
> ——那條是 Client 的時鐘，`GhCli.TIMEOUT_SECONDS` 是規格隻字未提的子行程預算。兩者混寫就是
> §4.2 犯過的那種錯。

- **30 秒的支撐，量出來了**：五個 Tool 形狀實測中位數 542–966 ms，最壞單次 1,474 ms。
  30 秒是最慢中位數的 31 倍、實測最壞值的 20 倍——不是「大概夠用」，是離常態兩個數量級。
- **一次 Tool 呼叫不等於一次 `gh`**：`add_issue_comment` 打兩次（GraphQL 沒辦法在同一份文件
  裡把查詢結果餵給 mutation），所以它的最壞牆鐘是 ~60 秒而其他四個是 ~30 秒。設 Client
  timeout 要看的是這個數，而它原本哪裡都沒寫。
- **取消通知：整個相依堆疊沒有實作**，收到會走 SDK 的未知通知分支（一行 WARN、不回應、
  不碰 stdout）。規格對接收方明列 **MAY ignore**，所以這是允許的忽略而非缺口，收成
  ADR-0016，代價寫清楚：Client 放棄後 `gh` 仍跑到自己的 30 秒。
- **順帶補了 ADR-0013 一個洞**：那行 WARN 會把 Client 自撰的 `reason` 原文寫進日誌。
  「shape, never content」畫的是 GitHub 內容那條線，Client 的文字在線外。
- **不動任何 production 程式碼**：全案是文件工作。

### 7.3 架構已正確、無需改動

#### ✓ Failure Contract 設計
- 按 Remedy（動作）而非 Cause（原因）分類，正確
- 五個 Remedy 足以涵蓋 `gh` 失敗類別
- Server 級統一處理，避免每個 Tool 複製邏輯
- **評估**：堅固設計，符合 ADR-0002, ADR-0008

#### ✓ Tool Annotations 與權限分離
- 規格禁止客戶端信任 annotations，本專案遵循
- `readOnlyHint` 作為分類名稱，非執行約束
- 測試層驗證聲明一致性 (WritePartitionAcceptanceTest)
- **評估**：正確的安全模式，不違反規格

#### ✓ GhCli 單一出口
- 所有 Tool 共享同一失敗分類與恢復邏輯
- 新 Tool 無需複製 failure handling 代碼
- **評估**：優良架構，降低錯誤風險

#### ✓ Tool 層與傳輸層分離
- Tool 邏輯（IssueTools, CommentTools 等）完全獨立於傳輸
- 轉換為 HTTP 不需改動 Tool 代碼
- **評估**：良好的分層設計

#### ✓ GraphQL 安全使用
- 變數依型別選 flag：`String` 值走 `-f`，`int` 值走 `-F`（`CommentTools.java:174-183`）
- 無 shell，ProcessBuilder 安全

#### ⚠ 一處文件與程式碼互相矛盾
`CommentTools.java:27-28` 以粗體斷言「Every GraphQL variable here is sent with `-f`, and none with `-F`」，但同一檔案 `:177` 與 `:183` 正是 `-F number=` 與 `-F last=`，而同一段 javadoc 的 `:40-42` 又寫明規則**不是**「avoid `-F`」、並指名這兩處 `-F` 是正確的。三者出自同一個 commit `ce833071`。粗體那句是假的。對一個把「凡是沒親眼見過的，不許寫進程式碼裡當事實」當成紀律的 repo，這句話出現在安全性最相關的那段 javadoc 開頭，值得修。
- 無 shell，ProcessBuilder 安全
- **評估**：安全的參數傳遞

#### ✓ 測試分層
- Acceptance layer 跨越 wire 邊界
- Coverage layer 單元測試邏輯
- 易於添加新 Tool 的測試
- **評估**：成熟的測試架構

---

## 8. 建議與行動項

### 立即行動（部署前必做）

1. **相依版本策略**：Java 25 是 LTS，維持不動。要決定的是 Spring Boot 4.1.1 / Spring AI 2.0.1 這兩個三週新的版本要不要釘住、多久追一次、出事退到哪裡——目前 repo 沒有這條記載。
   - 預期工作量：0.5 天（寫成一份 ADR 或 README 一節）

2. **HTTP 傳輸決策**：確認部署拓撲
   - 若單機或無負載均衡需求：保持 Stdio
   - 若多機部署或需反向代理：新建 HTTP variant (Spring MVC controller)

3. **多租戶隔離**：若目標是 SaaS，決定隔離模式
   - 選項 A：每租戶一進程 (推薦，改動最少)
   - 選項 B：重新設計認證層，在 Server 端實現每租戶權限檢查 (成本高)

### 上線前改善（視商用目標）

4. ~~**日誌結構化**~~：已完成（ECS，Boot 內建，ADR-0013）
5. ~~**OAuth 部署指南**~~：已完成（`docs/deploying.md`），且順帶更正了三處規格誤讀
6. ~~**監控指標**~~：已收成「不做，以及誰該做」（ADR-0014）
7. ~~**輸出大小限制**~~：已完成（8 MB 上限＋致命 Error 終止行程，ADR-0015）
8. **更新日誌**：~~timeout 政策~~（已完成，見 P5）、version 相容性

### 未來功能演進（無緊迫性）

9. **2026-07-28 規格支援**：等待 MCP Java SDK 3.0+
10. **MRTR (多回合要求)**：若 Client 支援，可加入確認對話框
11. **每 Tool 自訂 Timeout**：若有特殊需求，擴展 GhCli 設定機制

---

## 9. 總結評定

### 規格符合度

| 面向 | 評定 | 備註 |
|------|------|------|
| Protocol Negotiation | ✓ | 2025-11-25 fully supported |
| Tool & Annotations | ✓ | Spec compliant, security model correct |
| Error Handling | ✓ | isError + structuredContent per spec（邊界見 ADR-0011） |
| Features gap (2026-07-28) | ⚠️ | SDK-gated, not project defect |

### 架構品質

| 面向 | 評定 | 備註 |
|------|------|------|
| Separation of Concerns | ✓ | Tool, GhCli, Transport 清晰分層 |
| Error Contract | ✓ | 統一、可擴展、按 Remedy 分類 |
| Testability | ✓ | Acceptance + Coverage 完善 |
| Extensibility | ✓ | 新 Tool 邊際成本低 |

### 商用準備度

| 面向 | 評定 | 備註 |
|------|------|------|
| Single-tenant 部署 | ✓ | 五個 Tool 已端到端驅動過，見 §10 |
| Multi-tenant SaaS | ❌ | Blocker: 無隔離機制 |
| Distributed deployment | ⚠️ | Blocker: Stdio only |
| 資源上限 | ✓ | 單一回應 8 MB 上限，致命 Error 終止行程（ADR-0015） |
| Observability | ⚠️ | 日誌已結構化且每次呼叫留痕（§3）；metrics 知情不做（ADR-0014）；無送出管線 |
| 規格 MUST 缺口 | ❌ | 無限流（ADR-0012 記為知情偏離） |
| Dependency maturity | ✓ | Java 25 是 LTS；Spring 兩個版本都很新，見 §6 |

### 核心結論

**project_mcp 是一個架構設計優良的教學級 MCP Server；以 2025-11-25 為準它是符合規格的，以 2026-07-28（即「MCP 2.0」）為準則尚未符合，而缺口在 SDK 不在它自己。** 其 failure contract、tool 層設計、測試分層都遠超平均水準。

**商用部署前的門檻，按部署形狀分**：

**單機、單租戶**（例如內部 AI Agent 工具）：§7.1 的三個 blocker 沒有一個適用——
SDK 天花板只在客戶要求 2026-07-28 時才擋，stdio 與多租戶隔離講的都是別種部署形狀。
五個 Tool 已端到端驅動過（§10），可以上線。運維面的日誌部分已經補完（§3）：每次呼叫留痕、
ECS 結構化、內容不入檔。剩下的是部署文件，以及一件只有離開單機才成立的事——把日誌送出去。
Metrics 在這個形狀下是刻意不做的，不是欠的（ADR-0014）。

**分散式或多租戶 SaaS**：blocker #2 與 #3 都是硬牆，且 #3 的修復成本高——
要嘛每租戶一個進程，要嘛重新設計認證層。同時限流那條 MUST 在這種形狀下不再是可以記載的偏離，
必須實作。

**若客戶要求 2026-07-28（「MCP 2.0」）相容**：今天做不到，而且不在這個 repo 手上。
等 SDK 3.x（規劃 2026 年 9 月，見 §6.3）。

---

## 10. 實測覆蓋（2026-09-09）

**本評審的第 1 到 9 節初稿是讀原始碼寫的。** 那個方法找出了不少東西，也寫錯了兩件
（見 §1.1、§1.4 的校訂）。2026-09-09 把打包好的 jar 交給官方 MCP Inspector v2.5.0
與手寫 JSON-RPC 各驅動一輪，以下是實際跑過的範圍。

### 協議層

| 項目 | 結果 |
|---|---|
| stdout 純淨度（stdio 唯一的 MUST NOT） | ✓ 4 行 700 bytes，全部合法 JSON-RPC，非 MCP 行數 0，stderr 0 bytes |
| `protocolVersion` 協商 | ✓ `2025-11-25`（Inspector 標記 era = `LEGACY`） |
| `tools/list` schema | ✓ 五個都是合法 JSON Schema object、扁平、無 `outputSchema` |
| `tools/list` 順序穩定性 | 三次獨立 JVM 啟動間一致（規格是 SHOULD，未由建構保證，但實測穩定） |
| 協議層錯誤與 `isError` 分離 | ✓ 未知 tool → `-32602`，不走結果 |
| `ping` | ✓ |

### Tool 覆蓋

五個 Tool 全部驅動到成功，失敗側各有樣本。

| Tool | 成功 | 失敗 |
|---|---|---|
| `list_issues` | ✓ 含 `state`、`labels` 陣列過濾 | — |
| `get_issue` | ✓ | ✓ 不存在的 repo、PR 號碼 |
| `list_labels` | ✓ 含 `search` | — |
| `list_issue_comments` | ✓ **含分頁往返**（`nextCursor` 原樣送回，第二頁回不同留言） | ✓ 壞掉的 cursor |
| `add_issue_comment` | ✓ 真的寫進 sandbox | ✓ 空白 body、PR 號碼、逾時 |

參數夾值實測：`limit=999` → 夾到 100；`limit=0` → 夾到 1。
ADR-0003 的核心承諾（PR 號碼被拒）在讀取與寫入兩條路徑上都驗過。

### Remedy 覆蓋：五個全部實測到

| Remedy | 怎麼provoke 的 |
|---|---|
| `FIX_REQUEST` | 不存在的 repo、壞 cursor、PR 號碼、空白 body |
| `ASK_OPERATOR` | `gh` 不在 PATH；`GH_TOKEN` 無效（stderr 帶 HTTP 401） |
| `RETRY` | proxy 指到 `127.0.0.1:1`（連線被拒） |
| `UNKNOWN` | proxy 指到不存在的主機（見下） |
| `CHECK_BEFORE_RETRY` | 兩段式 CONNECT proxy：放行 lookup、黑洞 mutation |

**`CHECK_BEFORE_RETRY` 那一格值得多說一句。** `add_issue_comment` 打的是兩通不具原子性的
呼叫（先 `gh.run` 查 issue id，再 `gh.runWrite` 寫入）。黑洞落在第一通時回 `RETRY`，
落在第二通時回 `CHECK_BEFORE_RETRY`，訊息還點名要用 `list_issue_comments` 去確認。
證明的不只是「寫入逾時會給對的建議」，而是**這個 Server 分得清是哪一通逾時了**。

### 順帶量到的一件事

`GhStderr` 的註解邀請後人 provoke 網路失敗並貼回實際 stderr。provoke 了，`gh` 吐的是：

```
error connecting to this-host-does-not-exist.invalid
check your internet connection or https://githubstatus.com
```

**這是證據，不是建議。** 一個樣本、一種 provoke。同一句話可能也蓋到 TLS 失敗、
經過 proxy 的連線被拒、GitHub 掛掉，那幾種的正確 Remedy 不一定都是 `RETRY`。
用一個樣本撐一個涵蓋整族的 marker，正是 `a748fa4` 刪掉那四行的理由。

### 仍未測到

- rate limit → `RETRY`（要真的打爆 GraphQL 額度；ADR-0002 當年也 provoke 不出來）
- ~~超大回應 / OOM~~ → **2026-09-09 已測**，見 §4.4 與 ADR-0015。8 MB 上限、60 MB 的
  OOM 與殭屍行程、6.57 MB 的現實最壞形狀、容器內的 OOM 路徑，全部驅動過；兩個邊界有 wire
  層測試釘住
- 併發 → **2026-09-09 測到一半**：同一條 stdio 上，一個卡住 20 秒的 `gh` 不會擋住後續呼叫
  （後送的請求 27 ms 就回來，先送的 20.36 秒後才回，各自帶正確的 id，沒有插隊）。這是 SDK
  的排程行為不是本專案的，所以只當版本觀察不當承諾，也沒有加測試去釘別人的內部行為。
  沒測到的是**負載下**的併發——幾十個同時在跑時的行為
- 長時間連線

剩下的仍需真實流量形狀才測得有意義，現在測等於憑空猜負載。

---

## 參考資料

### MCP 規格
- [Model Context Protocol 2025-11-25 Specification](https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-11-25/index.mdx)
- [MCP Protocol Versions](https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/main/mcp-core/src/main/java/io/modelcontextprotocol/spec/ProtocolVersions.java) (MCP Java SDK)

### 專案文檔
- `docs/mcp-2025-11-25-conformance.html` — 逐條規格對照
- `docs/mcp-2025-11-25-commercial-primer.html` — 商用門檻的教材版
- `CONTEXT.md` — 域詞表與設計原理
- `docs/adr/0001` 至 `docs/adr/0012` — 架構決策記錄
- `README.md` — 概述與啟動指南

### 依賴版本查詢
- [Spring Boot 4.1.x Support](https://spring.io/projects/spring-boot#support)
- [Java 25 Release Notes](https://www.oracle.com/java/technologies/javase-jdk25-relnotes.html)
- [Java SE Support Roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)

### 原始碼檔案 (本評審引用)
- `src/main/java/io/github/demianli/projectmcp/tool/IssueTools.java` — Tool 宣告
- `src/main/java/io/github/demianli/projectmcp/tool/ToolResults.java` — 成功/失敗格式
- `src/main/java/io/github/demianli/projectmcp/gh/GhCli.java` — 子進程管理、超時、failure path
- `src/main/resources/application.yml` — 日誌與 web 配置

---

**評審完成日期**：2026-09-08

**評審員**：Claude Code (Haiku 4.5)

**下一步**：提交本評審至 ADR-0011（若專案繼續演進）或商用部署決策會議。

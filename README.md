# project_mcp

以 Tool 形式提供 **GitHub issue 與 label** 的 [MCP](https://modelcontextprotocol.io) Server。
它包裝 `gh` CLI：Client 取得五個事先宣告、輸入有型別的操作，完全不需要 shell 權限。
它包裝的是 `gh` 而不是 `git`，本機版本控制不在範圍內。

## 架構

```
MCP Client（例如 MCP Inspector，或 AI 應用程式的 host）
  └─ 以子行程啟動 Server
        ▲
        │  stdio 上的 JSON-RPC
        ▼
project_mcp（Spring Boot + Spring AI）
  ├─ 宣告五個 Tool
  ├─ 呼叫 `gh` CLI
  └─ log 只寫入檔案，從不寫到 stdout
```

Server 由 Client 以子行程啟動，與 Web 世界裡「server」的一般意思相反。各角色的精確定義見
[CONTEXT.md](./CONTEXT.md)（英文）。

**技術堆疊：** Maven、Java 25、Spring Boot 4.1、Spring AI 2.0（`spring-ai-starter-mcp-server`）、
MCP Java SDK 2.0。**協定版本：** 2025-11-25。**傳輸方式：** 僅 stdio。

## Tool

| Tool | 類型 | 功能 |
| --- | --- | --- |
| `list_issues` | 讀取 | 依狀態篩選，列出 repository 的 issue，由新到舊 |
| `get_issue` | 讀取 | 單一 issue 的完整內容，包含內文；pull request 編號會被拒絕 |
| `list_labels` | 讀取 | repository 的 label |
| `list_issue_comments` | 讀取 | 單一 issue 的留言，由新到舊，以 cursor 分頁 |
| `add_issue_comment` | 寫入 | 在 issue 新增一則留言，並回傳其永久連結 |

不提供 pull request：GitHub 的 issue 與 pull request 共用同一組編號，遇到 pull request 編號時
直接拒絕，而不是只回答一半。沒有 Resource，因為讀取 Resource 無法回報失敗。參數與結果格式見
[docs/design.md](./docs/design.md#tools)（英文）。

## 呼叫失敗時

每個失敗都以 `isError: true` 的 Tool result 回傳，而不是 JSON-RPC error，因此模型看得到。
其 `structuredContent` 帶有一個 **Remedy**，說明呼叫者下一步該做什麼：

| Remedy | 意義 |
| --- | --- |
| `RETRY` | 再呼叫一次相同的請求；有 `retryAfterSeconds` 時，等待該秒數後再試 |
| `CHECK_BEFORE_RETRY` | 寫入的結果無法讀取：先確認是否已經寫入 |
| `FIX_REQUEST` | 照原樣呼叫不可能成功；須修改參數 |
| `ASK_OPERATOR` | 呼叫者無法改變任何事；須由人修正環境 |
| `UNKNOWN` | 無法辨識的失敗；只能依 `stderr` 判斷 |

見 [docs/design.md](./docs/design.md#failure-contract)（英文）。

## 上限

| 項目 | 值 |
| --- | --- |
| 單次 `gh` 呼叫 | 30 秒（`add_issue_comment` 會呼叫兩次，因此一次 Tool 呼叫最多約 60 秒） |
| 單次 `gh` 回應 | 8 MB；超過時以 `FIX_REQUEST` 拒絕 |
| 列表的 `limit` | 預設 30，限制在 1–100 |
| 寫入（`add_issue_comment`） | 每個行程每分鐘 80 次、每小時 500 次，即 GitHub 公布的內容產生類請求上限；超過時回傳 `RETRY` 與 `retryAfterSeconds` |

## 安全注意事項

- **GitHub 內容原樣回傳。** Issue 內文、留言與 label 說明由任何能寫入該 repository 的人撰寫，
  原封不動送到模型。偽裝成指令的文字（prompt injection）也會一併送達。Client 必須把 Tool
  輸出視為不可信的資料，而不是指令。
- **一個行程就是一個身分。** 認證由 `gh` 負責：每次呼叫都使用 `gh` 解析出的登入身分，Server
  從不持有 token。Client 能寫入什麼，取決於該登入身分能寫入什麼。唯讀部署就是讓 `gh` 使用
  沒有寫入權限的登入身分；多租戶部署就是每個租戶一個行程。
- **讀取不受 rate limit。** 只有寫入受限。讀取對 GitHub 的成本取決於查詢內容，沒有公布的
  單次呼叫數字可以套用；GitHub 自身的限制依然存在，觸發時回傳 `RETRY`。

<a id="stability"></a>

## 相容性承諾

從 1.0.0 起，本 Server 遵循[語意化版本](https://semver.org/lang/zh-TW/)。公開契約包括：

- 五個 Tool 的名稱及其輸入 schema
- 每種成功結果的格式
- 五個 Remedy 值，以及每個失敗都以 `isError: true` 回報

不屬於契約：給人閱讀的句子、從 `gh` 轉傳的 `stderr` 文字，以及 log 行的格式。

## 建置與執行

需要 JDK 25、Maven，以及 `PATH` 上已登入的 `gh`。

```bash
mvn package
java -jar target/project-mcp-1.0.1.jar
```

**不要使用 `mvn spring-boot:run`。** Maven 會在應用程式啟動前把自己的輸出寫到 stdout，Client
會試圖把它當成 JSON-RPC 解析而失敗。一律執行打包好的 jar。

Log 寫入 `logs/project-mcp.log`：每次 Tool 呼叫一行 JSON，從不包含 issue 或留言的內容；
也從不寫到 console，因為 stdout 是協定通道。

## 文件

- [docs/design.md](./docs/design.md)（英文）：Server 如何運作，包括每個 Tool 的參數與結果格式、
  失敗契約、上限、logging，以及已知與規格不一致之處
- [docs/deploying.md](./docs/deploying.md)（英文）：部署必須提供與決定的事項。專案根目錄的
  [`Dockerfile`](./Dockerfile) 滿足其中每個條件
- [docs/architecture-tour.html](./docs/architecture-tour.html)：用一頁白話導覽整個 Server
- [docs/mcp-2025-11-25-conformance.html](./docs/mcp-2025-11-25-conformance.html)：2025-11-25
  規格對 stdio server 的要求，以及每項要求在哪裡實現
- [docs/mcp-2025-11-25-commercial-primer.html](./docs/mcp-2025-11-25-commercial-primer.html)：
  同樣的內容，寫給正在學習打造 MCP server 的人，每條規定旁附上專業術語
- [docs/mcp-client-server-dataflow.html](./docs/mcp-client-server-dataflow.html)：用四張圖呈現
  Client 與本 Server 之間的對話，每個術語都附註該去哪裡深入了解
- [docs/mcp-2026-07-28-dataflow.html](./docs/mcp-2026-07-28-dataflow.html)：同樣四張圖，對應
  2026-07-28 版本，並列出本 Server 尚未符合的要求
- [CONTEXT.md](./CONTEXT.md)（英文）：詞彙表，包括 Server、Client、Tool、Resource、Remedy 與
  各種傳輸方式
- [CHANGELOG.md](./CHANGELOG.md)：各版本的內容與相容性

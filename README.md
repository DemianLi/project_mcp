# project_mcp（TypeScript）

以 Tool 形式提供 GitHub 操作的 [MCP](https://modelcontextprotocol.io) Server，
講 **MCP 2026-07-28**（規格自稱 Modern）。

這是一份獨立的實作，不是 [`develop`](../../tree/develop) 上 Java 版的移植。兩者的核心互斥：
Java 版走 2025-11-25 的 `initialize` 握手，是有狀態的；2026-07-28 把握手整段刪除，版本與能力
改由每一則請求自帶。共用程式碼的代價高於重寫。

## 現況：骨架

協定層可以跑，**還沒有任何 Tool**。`tools/list` 回一份空菜單。

| 已經有 | 還沒有 |
| --- | --- |
| stdio 傳輸：一行一則，stdout 只走 MCP | 任何 Tool |
| 無狀態分派：每則請求自己讀 `_meta`，不看前一則 | `gh` CLI 的呼叫 |
| `server/discover`（規格 MUST） | 失敗契約與 Remedy |
| `resultType` 標在每個結果上（規格 MUST） | MRTR／`input_required` |
| 清單結果帶 `ttlMs` 與 `cacheScope`（規格 MUST） | 寫入的速率限制 |
| 版本不符回 `-32022`，並列出自己會的版本 | 取消（`notifications/cancelled`）的實際處理 |
| Log 寫 stderr，從不寫 stdout | |

## 為什麼沒有用官方 SDK

`@modelcontextprotocol/sdk` 1.30.0（本文寫作時的 latest）協定上限是 **2025-11-25**：
套件裡沒有 `resultType`、沒有 `server/discover`、沒有 `-32022`，它的 `Server` 類別環繞著
2026-07-28 已經刪除的 `initialize` 握手。要講 2026-07-28 就只能自己寫協定層。

Java 版卡在同一道天花板，成因也一樣——見
[`develop` 的 docs/mcp-2026-07-28-dataflow.html](../../blob/develop/docs/mcp-2026-07-28-dataflow.html)。

SDK 補上 2026-07-28 之後值得重新評估；在那之前，`src/protocol/` 就是這個專案的 SDK。

## 版面

```
src/
  main.ts              進入點：接上 stdin／stdout，stdin 關閉就結束
  log.ts               寫 stderr。記 Shape（方法、耗時、結果），不記 Content
  protocol/
    versions.ts        本 Server 講哪幾版，以及自報身分
    jsonrpc.ts         JSON-RPC 2.0 的形狀與錯誤碼
    meta.ts            每則請求的 `_meta`：版本、能力、身分
    results.ts         `resultType` 與清單的快取提示
    dispatch.ts        依 method 分派。沒有跨請求狀態
    stdio.ts           行框架，逐則序列處理
  tools/
    registry.ts        Tool 清單。目前是空的
test/
  wire.test.ts         Acceptance 層：真的啟動子行程，真的走 stdin／stdout
```

測試分兩層，沿用 Java 版的分工：`src/**/*.test.ts` 是 Coverage 層，不跨 wire 邊界；
`test/wire.test.ts` 是 Acceptance 層，測的是 Client 真正看得到的東西（stdout 乾不乾淨、
stdin 關閉會不會結束、一行一則）。

## 建置與執行

需要 Node.js 22.12 以上。

```bash
npm ci
npm test     # typecheck → build → 兩層測試
npm start    # 等同 node dist/main.js
```

手動對一句話：

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"server/discover"}' | npm start --silent
```

Log 走 stderr，因此上面那行看到的只有協定訊息。要看 log 就別把 stderr 丟掉。

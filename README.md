# project_mcp

以 Tool 形式提供 GitHub 操作的 [MCP](https://modelcontextprotocol.io) Server，
實作 **MCP 2.0（2026-07-28）**。

## 骨架

協定層可以跑，還沒有任何 Tool——`tools/list` 回一份空菜單。

- stdio 傳輸：一行一則，stdout 只走 MCP 訊息
- 無狀態分派：每則請求自己讀 `_meta`（版本、能力、身分），不看前一則
- `server/discover`：不需要先講對版本就能問
- `resultType` 標在每一個結果上
- 清單結果帶 `ttlMs` 與 `cacheScope`
- 版本不符回 `-32022`，並列出自己支援的版本；未知工具回 `-32602`
- Log 寫 stderr，從不寫 stdout

## 版面

```
src/
  main.ts              進入點：接上 stdin／stdout，stdin 關閉就結束
  log.ts               寫 stderr。記方法、耗時、結果，不記 issue 與留言內容
  protocol/
    versions.ts        本 Server 講哪幾版，以及自報身分
    jsonrpc.ts         JSON-RPC 2.0 的形狀與錯誤碼
    meta.ts            每則請求的 `_meta`
    results.ts         `resultType` 與清單的快取提示
    dispatch.ts        依 method 分派
    stdio.ts           行框架，逐則序列處理
  tools/
    registry.ts        Tool 清單
test/
  wire.test.ts         Acceptance 層：啟動子行程，走 stdin／stdout
```

測試分兩層：`src/**/*.test.ts` 不跨 wire 邊界，`test/wire.test.ts` 測 Client 真正看得到的
東西——stdout 乾不乾淨、stdin 關閉會不會結束、一行一則。

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

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

每個檔案只有一個改動的理由。

```
src/
  main.ts              進入點與組裝：讀行 → 處理 → 寫行 → 記錄
  log.ts               寫 stderr。記方法、耗時、結果，不記 issue 與留言內容
  declarations.ts      Server 對自己的宣告：serverInfo、capabilities
  protocol/
    versions.ts        支援的版本
    errors.ts          錯誤碼
    messages.ts        JSON-RPC 訊息型別與建構
    parse.ts           一行文字 → 訊息
    meta.ts            檢查每則請求的 `_meta`
    results.ts         `resultType` 與清單的快取提示
    router.ts          請求 → handler，以及所有請求都要過的版本關卡
    handle.ts          一行進、一行出。不碰 stream
    methods/
      discover.ts      `server/discover`
      toolsList.ts     `tools/list`
      toolsCall.ts     `tools/call`
  transport/
    stdio.ts           行框架與 IO。不認識 JSON，也不認識 MCP
  tools/
    registry.ts        Tool 定義與清單
scripts/
  ask.mjs              對建好的 Server 問一句話
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

手動問一句話。`_meta` 由腳本填上：

```bash
npm run ask server/discover
npm run ask tools/list
npm run ask tools/call '{"name":"list_labels","arguments":{}}'
```

MCP Inspector：

```bash
npm run inspect        # Web UI
npm run inspect:cli -- --method tools/list
```

Inspector 2.7.0 講到 2025-11-25，連線時送 `initialize`，本 Server 回
`-32602 _meta is required on every request` 後它就停住。等它支援 2026-07-28 這兩個指令即可使用。

Log 走 stderr，協定訊息走 stdout。

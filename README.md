# project_mcp

以 Tool 形式提供 GitHub 操作的 [MCP](https://modelcontextprotocol.io) Server，
實作 **MCP 2.0（2026-07-28）**。

## 骨架

協定層可以跑，一個 Tool：`get_weather`。它不連網、回寫死的資料，存在的目的是當範本——
示範一個 Tool 怎麼宣告自己、怎麼驗參數、怎麼回成功、怎麼回失敗。

- stdio 傳輸：一行一則，stdout 只走 MCP 訊息
- 無狀態分派：每則請求自己讀 `_meta`（版本、能力、身分），不看前一則
- `server/discover`：不需要先講對版本就能問
- `resultType` 標在每一個結果上
- 清單結果帶 `ttlMs` 與 `cacheScope`
- 版本不符回 `-32022`，並列出自己支援的版本；未知工具回 `-32602`
- 工具失敗走 `isError: true` 的結果而不是 JSON-RPC error，帶一個 Remedy 說明下一步：
  `RETRY`、`CHECK_BEFORE_RETRY`、`FIX_REQUEST`、`ASK_OPERATOR`、`UNKNOWN`
- PostgreSQL 連線池，懶建：沒有人查詢就沒有連線
- Log 寫 stderr，從不寫 stdout

## 版面

每個檔案只有一個改動的理由。

```
src/
  main.ts              進入點與組裝：讀行 → 處理 → 寫行 → 記錄 → 關池
  log.ts               寫 stderr。記方法、耗時、結果，不記呼叫的內容
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
    definition.ts      一個 Tool 長什麼樣子
    registry.ts        Tool 清單。加 Tool 只動這裡
    remedy.ts          五個 Remedy
    result.ts          工具結果外殼：成功與失敗
    getWeather.ts      範本 Tool
  db/
    config.ts          連線池設定，全部從環境變數讀
    pool.ts            連線池、query、交易、關池
scripts/
  ask.mjs              對建好的 Server 問一句話
test/
  wire.test.ts         Acceptance 層：啟動子行程，走 stdin／stdout
Dockerfile             兩階段 image。不開 port，沒有 healthcheck
compose.yaml           PostgreSQL ＋ 接上它的 Server
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
npm run ask tools/call '{"name":"get_weather","arguments":{"city":"Taipei"}}'
```

MCP Inspector：

```bash
npm run inspect        # Web UI
npm run inspect:cli -- --method tools/list
```

Inspector 2.7.0 講到 2025-11-25，連線時送 `initialize`，本 Server 回
`-32602 _meta is required on every request` 後它就停住。等它支援 2026-07-28 這兩個指令即可使用。

### Docker

```bash
docker build -t project-mcp .
docker run -i --rm project-mcp
```

`-i` 是必要的：Server 從 stdin 讀，沒有 stdin 就等於開機即關機。這個 image 不開 port、
也沒有 healthcheck——stdio Server 沒有可以探測的端點，而往 stdout 寫探測結果會弄壞協定通道。

`ask.mjs` 可以改問容器裡的 Server：

```bash
MCP_SERVER_CMD='docker run -i --rm project-mcp' npm run ask tools/list
```

要連資料庫就用 compose。Server 不是常駐服務，所以用 `run` 而不是 `up`，`-T` 關掉 TTY 讓
stdin／stdout 維持乾淨的管子：

```bash
docker compose run --rm -T mcp
docker compose up -d db      # 只要資料庫
```

### 配置資料庫

複製 [`.env.example`](./.env.example) 成 `.env`，填 `DATABASE_URL`，然後：

```bash
node --env-file=.env dist/main.js
```

只有 `DATABASE_URL` 必填，其餘（池大小、三種逾時）有預設值。整段不填也可以——Server 照常
啟動，只是碰資料庫的 Tool 會拿到 `DatabaseNotConfiguredError`。

池子是懶建的：第一次查詢才連線，所以不用資料庫的部署與離線的測試都不需要一個 PostgreSQL。

寫一個增刪改查的 Tool：

```ts
import { query, withTransaction } from '../db/pool.js';
import { Remedy } from './remedy.js';
import { ok, failed } from './result.js';
import type { ToolDefinition } from './definition.js';

export const listCustomers: ToolDefinition = {
  name: 'list_customers',
  description: 'Customers, newest first.',
  inputSchema: { type: 'object', properties: { limit: { type: 'integer' } } },
  annotations: { readOnlyHint: true },
  call: async (args) => {
    const limit = typeof args['limit'] === 'number' ? args['limit'] : 30;
    const rows = await query('select id, name from customers order by id desc limit $1', [limit]);
    return ok({ items: rows, count: rows.length });
  },
};
```

多步驟的寫入用 `withTransaction`，不要自己 acquire 連線——忘記 release 是連線池最常見的死法。
寫好之後把它加進 [`src/tools/registry.ts`](./src/tools/registry.ts) 的 `TOOLS`，協定層不必動。
失敗時回 `failed(Remedy.X, '...')`：環境壞掉是 `ASK_OPERATOR`，參數不對是 `FIX_REQUEST`。

Log 走 stderr，協定訊息走 stdout。

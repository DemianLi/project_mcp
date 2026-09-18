# project_mcp

以 TypeScript 寫的 [MCP](https://modelcontextprotocol.io) Server 骨架，
實作 **MCP 2.0（2026-07-28）**，協定層用官方的 `@modelcontextprotocol/server`。

## 骨架

四個 Tool，每一個都是一種範本：

| Tool | 範本示範的事 |
| --- | --- |
| `get_weather` | 一個 Tool 的四個部位：怎麼宣告自己、驗參數、回成功、回失敗。不連網，回寫死的資料 |
| `list_notes` | 用連線池讀一張表 |
| `add_note` | 用 `withTransaction` 寫入 |
| `delete_note` | 多回合流程（MRTR）：破壞性動作先問過人再做 |

- stdio 傳輸：一行一則，stdout 只走 MCP 訊息
- 協定機制交給 SDK：無狀態分派、每則請求自帶的 `_meta`（版本、能力、身分）、
  `resultType`、清單結果的 `ttlMs` 與 `cacheScope`
- 只講 2026-07-28。舊版開場（`initialize`，或任何不宣告版本的請求）回 `-32022`，
  並列出自己支援的版本
- `tools/list` 帶 `ttlMs: 60000` 與 `cacheScope: private`；未知工具回 `-32602`
- 參數形狀由 Tool 自己的 zod schema 擋，SDK 同時把它轉成 JSON Schema 放進 `tools/list`
- Tool 自己回報的失敗走 `isError: true` 的結果而不是 JSON-RPC error，帶一個 Remedy
  說明下一步：`RETRY`、`CHECK_BEFORE_RETRY`、`FIX_REQUEST`、`ASK_OPERATOR`、`UNKNOWN`。
  資料庫丟出來的例外由 `dbFailure.ts` 按 SQLSTATE 翻成這五種
- 多回合流程（MRTR）：Tool 回 `input_required`，Client 問到答案再重送同一個請求。
  兩回合之間的狀態放在 `requestState`，用 HMAC 封起來——它經過 Client 的手，回來時
  是攻擊者控制的輸入
- PostgreSQL 連線池，懶建：沒有人查詢就沒有連線。沒設 `DATABASE_URL` 時 Server 照常
  啟動，碰資料庫的 Tool 回 `ASK_OPERATOR`
- Log 寫 stderr，從不寫 stdout

## 版面

每個檔案只有一個改動的理由。

```
src/
  main.ts              進入點與組裝：接上 stdio、選版本、記錄、關機
  server.ts            Server 工廠：宣告 ＋ 註冊 Tools
  log.ts               寫 stderr。記方法、耗時、結果，不記呼叫的內容
  declarations.ts      Server 對自己的宣告：serverInfo、capabilities、快取提示、版本
  security/
    requestState.ts    多回合狀態的 HMAC 封裝
  tools/
    definition.ts      一個 Tool 長什麼樣子，以及怎麼掛到 Server 上
    registry.ts        Tool 清單。加 Tool 只動這裡
    remedy.ts          五個 Remedy
    result.ts          工具結果外殼：成功與失敗
    dbFailure.ts       SQLSTATE → Remedy
    getWeather.ts      範本 Tool：不碰外部世界
    notes/
      listNotes.ts     範本 Tool：讀
      addNote.ts       範本 Tool：寫，走交易
      deleteNote.ts    範本 Tool：多回合，先確認再刪
  db/
    config.ts          連線池設定，全部從環境變數讀
    pool.ts            連線池、query、交易、關池
db/
  schema.sql           示範資料表。compose 自動套用，CI 用 psql 套用
scripts/
  ask.mjs              對建好的 Server 問一句話
  inspect-docker.sh    建 image，起資料庫，再把 Inspector 接上去
test/
  support/client.ts    Acceptance 層共用的 Client
  wire.test.ts         協定怎麼接的。不碰資料庫
  notes.wire.test.ts   碰真的資料庫。沒有 DATABASE_URL 就整段跳過
Dockerfile             兩階段 image。不開 port，沒有 healthcheck
compose.yaml           PostgreSQL ＋ 接上它的 Server。資料放具名 volume
```

測試分兩層：`src/**/*.test.ts` 不跨 wire 邊界，`test/*.wire.test.ts` 測 Client 真正看得到的
東西。協定的實作是 SDK 的，所以 Acceptance 層測的不是它對不對，而是「我們把它接成了
什麼」——講哪一版、菜單上有誰、清單多久算新鮮、log 有沒有汙染協定通道。

碰資料庫的那一段沒有 `DATABASE_URL` 就整段跳過，所以離線也能 `npm test`。CI 跑兩輪：
一輪沒有資料庫（守住「沒資料庫也要能跑」這件事），一輪有 service container（讓資料庫
那條路不是只被手動驗過一次）。

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
npm run inspect:cli -- --method tools/call --tool-name get_weather --tool-arg city=Taipei
```

兩個指令都帶了 `--protocol-era modern`。Inspector 對臨時指定的 target **預設走 legacy**，
那會送 `initialize`，本 Server 不做雙版相容，只會回 `-32022`。`auto` 也可以：它先探測再決定。

### Docker

一行就有一整套：Server 加一個 PostgreSQL，再用 Inspector 連上去。

```bash
npm run inspect:docker                                    # Web UI
npm run inspect:docker -- --cli --method tools/list
npm run inspect:docker -- --cli --method tools/call \
  --tool-name get_weather --tool-arg city=Taipei
```

它做的事：`docker compose build mcp`、把資料庫叫起來等它 healthy、把 `docker compose run`
包成一個暫時的啟動腳本（Inspector 的 target 只吃一個字，多字詞會被拆錯），然後帶
`--protocol-era modern` 起 Inspector。Server 從 compose 起，所以它拿得到 `DATABASE_URL`，
碰資料庫的 Tool 不必另外設定。

結束時暫存檔會刪掉，**資料庫留著**——收掉用 `docker compose down`。資料在 `db-data` 這個
volume 裡，`down` 不會清掉它。

手動做也可以：

```bash
docker compose run --rm -T mcp    # Server ＋ 資料庫
docker compose up -d db           # 只要資料庫
```

`-T` 關掉 TTY，stdin／stdout 才是乾淨的管子。Server 走 stdio，不是常駐服務，所以用 `run`
而不是 `up`。這個 image 不開 port、也沒有 healthcheck——stdio Server 沒有可以探測的端點，
而往 stdout 寫探測結果會弄壞協定通道。

不要資料庫的話，單獨跑那個 image 就好：

```bash
docker build -t project-mcp:dev .
docker run -i --rm project-mcp:dev
MCP_SERVER_CMD='docker run -i --rm project-mcp:dev' npm run ask tools/list
```

`-i` 是必要的：Server 從 stdin 讀，沒有 stdin 就等於開機即關機。

### 配置資料庫

用 compose 跑的話 `DATABASE_URL` 已經設好了（`npm run inspect:docker` 也是），不必再做什麼。
在本機跑就複製 [`.env.example`](./.env.example) 成 `.env`，填 `DATABASE_URL`，然後：

```bash
node --env-file=.env dist/main.js
```

只有 `DATABASE_URL` 必填，其餘（池大小、三種逾時）有預設值。整段不填也可以——Server 照常
啟動，只是碰資料庫的 Tool 會回一個 `ASK_OPERATOR` 的失敗。

池子是懶建的：第一次查詢才連線，所以不用資料庫的部署與離線的測試都不需要一個 PostgreSQL。

資料表定義在 [`db/schema.sql`](./db/schema.sql)。compose 把它掛進 PostgreSQL 的
`/docker-entrypoint-initdb.d`，所以資料庫第一次建立時自動套用；volume 已經有資料就不會再跑，
要重來是 `docker compose down -v`。CI 沒有 compose，直接用 `psql` 套用同一個檔案。

### 寫自己的 Tool

照著 [`src/tools/notes/`](./src/tools/notes) 改就好——三個檔案分別是讀、寫、多回合：

- [`listNotes.ts`](./src/tools/notes/listNotes.ts) 一次 `query` 就夠的讀取
- [`addNote.ts`](./src/tools/notes/addNote.ts) 用 `withTransaction` 的寫入。不要自己
  `connect()`——忘記 `release()` 是連線池最常見的死法
- [`deleteNote.ts`](./src/tools/notes/deleteNote.ts) 破壞性動作，先問過人再做

參數的形狀交給 zod schema，`call` 裡只處理「形狀對但做不到」的失敗。資料庫的例外丟給
[`databaseFailure`](./src/tools/dbFailure.ts)，它按 SQLSTATE 決定 Remedy：逾時與死結是
`RETRY`，違反約束是 `FIX_REQUEST`，資料表不在或連不上是 `ASK_OPERATOR`。寫好之後把它加進
[`src/tools/registry.ts`](./src/tools/registry.ts) 的 `TOOLS`，其他地方不必動。

### 多回合流程（MRTR）

2026-07-28 沒有 Server 主動發請求這回事。要問 Client 一件事，做法是回一個
`input_required`，把問題放進 `inputRequests`，這一回合就結束；Client 問到答案之後，帶著
`inputResponses` 重送同一個請求，Tool 這次才動手。

兩回合之間 Server 不記任何東西——狀態封在 `requestState` 裡交給 Client 保管。它經過
Client 的手，所以回來時是攻擊者控制的輸入：[`src/security/requestState.ts`](./src/security/requestState.ts)
用 SDK 的 HMAC codec 封它，被改過、過期、或換一個 method 回來的，SDK 在進到 Tool 之前就回
`-32602`。

走 stdio 時一個行程服務整個流程，所以那把金鑰開機時隨機產生就夠。**改成 HTTP、或是多個
行程可能接到同一段流程的不同回合時，必須設 `REQUEST_STATE_SECRET`**（至少 32 bytes）讓它們
共用同一把，否則第二回合會驗不過。

Log 走 stderr，協定訊息走 stdout。

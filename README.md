# project_mcp

以 TypeScript 寫的 [MCP](https://modelcontextprotocol.io) Server 骨架，
實作 **MCP 2.0（2026-07-28）**，協定層用官方的 `@modelcontextprotocol/server`。

兩個進入點、同一批 Tool：開發時走 **stdio**（Claude Desktop 以子行程啟動），
上線時走 **Streamable HTTP**（k8s 裡的 Pod）。

## 骨架

四個 Tool，每一個都是一種範本：

| Tool | 範本示範的事 |
| --- | --- |
| `get_weather` | 一個 Tool 的四個部位：怎麼宣告自己、驗參數、回成功、回失敗。不連網，回寫死的資料 |
| `list_notes` | 用連線池讀一張表 |
| `add_note` | 用 `withTransaction` 寫入 |
| `delete_note` | 多回合流程（MRTR）：破壞性動作先問過人再做 |

- 兩種傳輸共用同一個 Server 工廠：stdio（一行一則，stdout 只走 MCP 訊息）與
  Streamable HTTP（`/mcp`，外加 `/healthz` 與 `/readyz` 兩個探測端點）
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
- 驗證層是一個接縫：`none`（開發）或 `jwt`（驗中央簽發的 token，只認非對稱簽章）。
  機關代碼進稽核紀錄，也綁進多回合流程的狀態
- 每個 Tool 宣告自己要的 scope，權限不足回 403 `insufficient_scope`，而且留下稽核紀錄
- HTTP 的守門：綁 loopback 是開發，什麼都可以省；綁其他位址少了驗證層（或明寫的
  無驗證宣告）與 `REQUEST_STATE_SECRET` 就拒絕啟動
- 關機照 k8s 的節奏：readiness 先轉紅、等一段時間、才停止收新連線並排空在飛的呼叫
- Log 寫 stderr，從不寫 stdout

## 版面

每個檔案只有一個改動的理由。

```
src/
  main.ts              stdio 進入點。開發時用
  httpMain.ts          HTTP 進入點。上線時用
  http/
    config.ts          HTTP 設定與對外服務的守門
    health.ts          liveness 與 readiness 的答案
    routes.ts          哪個路徑交給誰
  server.ts            Server 工廠：宣告 ＋ 註冊 Tools
  log.ts               寫 stderr。記方法、耗時、結果，不記呼叫的內容
  declarations.ts      Server 對自己的宣告：serverInfo、capabilities、快取提示、版本
  audit.ts             稽核紀錄：誰呼叫了什麼、結果如何
  auth/
    principal.ts       「這個請求是誰送來的」
    config.ts          驗證層設定
    authenticator.ts   接縫：headers → 機關身分
    context.ts         從請求上下文取機關代碼
    scopes.ts          誰可以呼叫哪個 Tool
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
  support/client.ts    stdio 的 Acceptance Client
  support/httpClient.ts HTTP 的 Acceptance Client
  support/tokens.ts    測試用的簽發端，簽真的 ES256
  wire.test.ts         協定怎麼接的。不碰資料庫
  http.wire.test.ts    探測端點、守門、關機順序
  auth.wire.test.ts    驗證層與稽核紀錄
  notes.wire.test.ts   碰真的資料庫。沒有 DATABASE_URL 就整段跳過
deploy/k8s/            部署範本與上線前要決定的事
Dockerfile             兩階段 image。一個 image，兩個進入點
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
npm test          # typecheck → build → 兩層測試
npm start         # stdio，等同 node dist/main.js
npm run start:http   # HTTP，預設聽 127.0.0.1:8080
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

### 接上 Claude Desktop

開發時走 stdio。先 `npm run build`，再把這段加進 `claude_desktop_config.json`
（macOS 在 `~/Library/Application Support/Claude/`，Windows 在 `%APPDATA%\\Claude\\`）：

```json
{
  "mcpServers": {
    "project-mcp": {
      "command": "node",
      "args": ["/絕對路徑/project_mcp/dist/main.js"],
      "env": { "DATABASE_URL": "postgres://mcp:mcp@localhost:5432/mcp" }
    }
  }
}
```

路徑要絕對的，Claude Desktop 不會用你的 shell 環境。改完重啟 Claude Desktop。
Server 的 log 走 stderr，Claude Desktop 會收進它自己的 log 檔。

### HTTP

上線時走 Streamable HTTP。

```bash
npm run start:http                      # 127.0.0.1:8080，開發用
curl -s http://127.0.0.1:8080/healthz   # 活著嗎
curl -s http://127.0.0.1:8080/readyz    # 可以送流量嗎
```

送一則 MCP 請求。HTTP binding 要求 **header 與 body 講同一件事**——`Mcp-Method` 要對上
`method`，`tools/call` 還要 `Mcp-Name` 對上 `params.name`。這是為了讓中間層（閘道、
稽核、授權）不必解 body 就能做事：

```bash
curl -s -X POST http://127.0.0.1:8080/mcp \
  -H 'content-type: application/json' \
  -H 'accept: application/json, text/event-stream' \
  -H 'Mcp-Method: tools/call' -H 'Mcp-Name: get_weather' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
        "name":"get_weather","arguments":{"city":"Taipei"},
        "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                 "io.modelcontextprotocol/clientCapabilities":{}}}}'
```

設定：

| 變數 | 預設 | 說明 |
| --- | --- | --- |
| `MCP_HTTP_HOST` | `127.0.0.1` | 綁 loopback 是開發，綁其他位址是上線 |
| `MCP_HTTP_PORT` | `8080` | `0` 表示交給作業系統挑 |
| `MCP_HTTP_PATH` | `/mcp` | MCP 端點 |
| `MCP_HTTP_SHUTDOWN_GRACE_MS` | `5000` | readiness 轉紅之後等多久才開始排空 |
| `MCP_HTTP_ALLOW_UNAUTHENTICATED` | 未設 | 對外服務時必須明寫 `yes-i-know` |

**綁非 loopback 的位址時，少了下面任一項就拒絕啟動**：`MCP_HTTP_ALLOW_UNAUTHENTICATED=yes-i-know`
（承認目前沒有驗證層），以及 `REQUEST_STATE_SECRET`（多回合狀態的金鑰，副本之間必須同一把）。
這兩道是刻意的：開發時麻煩一點只是麻煩，上線時少一道就是把服務裸奔在網路上。

在容器裡跑 HTTP **一定要設 `MCP_HTTP_HOST=0.0.0.0`**。預設只綁 loopback，所以 `-p` 對映
或 k8s 的 Service 都連不進來，而行程看起來一切正常——啟動時會記一行 `notice` 提醒這件事。

部署到 k8s 看 [`deploy/k8s/`](./deploy/k8s)。

### 驗證

預設 `MCP_AUTH_MODE=none`：不驗身分。只有綁 loopback 的部署可以這樣，這也是開發時
走 stdio 的情況——能啟動子行程的人就是擁有者。

要驗身分就設 `MCP_AUTH_MODE=jwt`。這台是 **resource server**：只驗 token，不發 token。
發放留在外面，日後換成甲方既有的簽入系統時，改的是幾個環境變數，程式碼不動。

```bash
MCP_AUTH_MODE=jwt \
MCP_AUTH_JWT_ISSUER=https://auth.example.gov.tw \
MCP_AUTH_JWT_AUDIENCE=https://mcp.example.gov.tw \
MCP_AUTH_JWT_JWKS_URL=https://auth.example.gov.tw/.well-known/jwks.json \
npm run start:http
```

驗的是簽章、`iss`、`aud`、`exp`。`sub`（或 `client_id`）就是呼叫者代碼。

三件刻意的事：

- **只認非對稱簽章**（預設 `ES256,RS256`）。`HS*` 會被拒絕——共用密鑰表示這台自己就
  簽得出 token，而它是對外的那一台。
- **`aud` 必填**。少了它，同一個簽發端發給別的系統的 token 也能打進這台。
- **401 不說原因**。過期、簽章不對、受眾不符，對攻擊者是三種不同的提示，所以回應裡
  分不出來；細節只留在 Server 自己的 log。

設好驗證之後，綁非 loopback 位址就不必再寫 `MCP_HTTP_ALLOW_UNAUTHENTICATED`。

### 權限

每個 Tool 宣告自己要的 scope，三級並列（不是包含關係——要能刪就要明寫 `notes:delete`）：

| Tool | 需要 |
| --- | --- |
| `get_weather` | 無。它回寫死的資料，不碰任何人的東西，通過驗證就能呼叫 |
| `list_notes` | `notes:read` |
| `add_note` | `notes:write` |
| `delete_note` | `notes:delete` |

token 的 `scope` claim 是空白分隔的字串（有些簽發端給陣列，兩種都收）：

```
"scope": "notes:read notes:write"
```

權限不足回 **403 `insufficient_scope`**，並在 `WWW-Authenticate` 與回應裡講清楚缺哪一個
——對方已經通過驗證，講清楚是幫他去申請，不是洩漏。**被拒絕的呼叫一樣留稽核紀錄**
（`outcome: "denied"`）：「某機關試圖刪除」正是稽核最需要回答的一種問題。

檢查有兩處，查的是同一份對照表。HTTP 路由依 `Mcp-Name` 擋下請求，這是實際生效的那一處；
`defineTool` 依實際要跑的 Tool 再檢查一次。第二處今天在 HTTP 上碰不到（header 與 body
不一致時 SDK 會先回 `-32020`），留著是為了不讓授權的正確性依賴那個 header 檢查存在。

沒有驗證層時一律放行——沒有身分就沒有授權可言，而那種部署已經被啟動守門限制在 loopback 上。

### 稽核

每一次 Tool 呼叫在 stderr 留一行：

```json
{"at":"...","event":"audit","agency":"LG-042","tool":"delete_note","outcome":"ok","ms":12}
```

記的是 Shape 不是 Content：哪個機關、哪個 Tool、成不成功、花多久——不含參數，也不含
回傳的資料。要看內容應該去查資料本身，而不是翻 log。`outcome` 有五種：`ok`、`failed`
（Tool 回報的失敗）、`input_required`（多回合的第一回合）、`denied`（權限不足）、
`threw`（沒接住的例外）。

沒有驗證層時 `agency` 是 `anonymous`——在 log 裡看到它就知道那台沒有驗證層。

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

要限制某個 Tool 只給部分機關用，就在它的定義裡宣告 `requiredScope`；不宣告就是任何通過
驗證的人都能呼叫。

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

走 stdio 時一個行程服務整個流程，所以那把金鑰開機時隨機產生就夠。走 HTTP 就不是了：
兩個回合不保證落在同一個 Pod，所以 **`REQUEST_STATE_SECRET`（至少 32 bytes）變成必填**，
而且每個副本要同一把，否則第二回合會被判成偽造。Server 綁非 loopback 位址時找不到它
就拒絕啟動。

Log 走 stderr，協定訊息走 stdout。

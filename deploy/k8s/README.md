# 部署到 k8s

這裡的 manifest 是**範本**，不是可以直接 apply 的成品：image、網域、資源上限、
NetworkPolicy 的來源都要換成甲方環境的值。

套用順序：

```bash
kubectl apply -f secret.example.yaml   # 換掉裡面的值，或改用甲方既有的 Secret 管理方式
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
```

## 驗證層

Server 是 **resource server**：它只驗 token，不發 token。發放那一端故意留在外面，這樣
日後甲方導入既有的單一簽入或 Keycloak 之類的東西時，只要換 `MCP_AUTH_JWT_*` 幾個變數，
Server 一行不用改。

設定 `MCP_AUTH_MODE=jwt` 之後就不必再寫 `MCP_HTTP_ALLOW_UNAUTHENTICATED`。

驗的東西：簽章（只認非對稱演算法）、`iss`、`aud`、`exp`。`sub`（或 `client_id`）就是機關
代碼，會進稽核紀錄，也會綁進多回合流程的狀態——甲機關批准的刪除，乙機關重送不算數。

**只給這台公鑰。** `MCP_AUTH_JWT_ALGORITHMS` 拒絕 `HS*`：共用密鑰表示這台自己就簽得出
token，而它是對外的那一台。

**`aud` 不是可選的。** 少了它，地方機關手上任何一個由同一個簽發端發出的 token 都能打進
這台，包含原本是給別的系統用的。

### 還沒有簽發服務時

驗證的程式碼在兩種情況下完全一樣，所以可以先上驗證、之後再補簽發：

1. **現在**：中央機關用一把私鑰簽中等效期（7～30 天）的 token，手動發給各機關。
   Server 設 `MCP_AUTH_JWT_PUBLIC_KEY`（PEM 公鑰）。
2. **之後**：架一個 token 端點，各機關用長效憑證自動換短效 token
   （OAuth 2.0 client_credentials）。Server 改設 `MCP_AUTH_JWT_JWKS_URL`，其他不動，
   地方機關那邊才需要改成自動換發。

這樣可以在不先蓋一個授權服務的前提下，把「沒有驗證」這個狀態先關掉。

### 值得向甲方提的另一條路

如果地方機關日後拿得到**政府憑證（GCA 機關憑證）**，mTLS 會比自建 token 服務省事：
身分基礎設施已經存在、而且已經被治理過，ingress 終結 mTLS、把 subject DN 當機關代碼
傳進來就好。Server 這邊多的是 `src/auth/` 裡的一個實作，Tool、稽核、多回合狀態都不動——
接縫是為這件事留的。目前地方機關沒有機關憑證，所以先走 JWT。

## 上線前一定要決定的事

**如果還沒設驗證層**，`MCP_HTTP_ALLOW_UNAUTHENTICATED=yes-i-know` 是刻意要你手動寫上去的，
意思是「我知道任何連得到這個 port 的人都能呼叫所有 Tool，包括會刪資料的」。在那之前
請務必用 NetworkPolicy 把來源限制在已知的地方機關網段。

**`REQUEST_STATE_SECRET` 是必填的。** 多回合的 Tool（例如 `delete_note`）第一回合與第二回合
不保證落在同一個 Pod；那份狀態是用這把金鑰封的，每個副本必須共用同一把，否則第二回合
會被判成偽造。Server 在非 loopback 的位址上找不到它就會拒絕啟動。

**連線數要算。** `DATABASE_POOL_MAX` × 副本數 不能超過 PostgreSQL 的 `max_connections`
（預設 100）。三個副本配 `DATABASE_POOL_MAX=10` 就是 30 條，還有餘裕給維運。

**稽核紀錄要收走。** Server 把 `{"event":"audit","agency":...,"tool":...,"outcome":...}`
寫到 stderr，一行一則 JSON。政府機關的案子通常要求可追溯，所以叢集的日誌收集要涵蓋它，
保存期限依甲方的規定。記的是 Shape 不是 Content——哪個機關呼叫了哪個 Tool、成不成功、
花多久，不含參數與回傳的資料。

## 兩個探測端點

| 端點 | 問的是 | 答不的時候 k8s 做什麼 |
| --- | --- | --- |
| `/healthz` | 行程還活著嗎 | 重啟這個 Pod |
| `/readyz` | 現在可以送流量進來嗎 | 把它移出 Service 的後端 |

`/healthz` **不碰資料庫**是刻意的：資料庫掛掉時重啟 Server 沒有幫助，只會把一場資料庫
事故變成一場滾動重啟事故。`/readyz` 才問資料庫。

## 關機

收到 SIGTERM 之後，Server 先讓 `/readyz` 轉紅並等 `MCP_HTTP_SHUTDOWN_GRACE_MS`（預設 5 秒），
才停止接受新連線、等在飛的 Tool 呼叫做完、收掉連線池。這段等待是必要的：kubelet 送
SIGTERM 與 Endpoints 把 Pod 拿掉是兩件並行的事，沒有先後保證。

`terminationGracePeriodSeconds` 要大於 grace ＋ 最慢的一次 Tool 呼叫。

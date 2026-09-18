# 部署到 k8s

這裡的 manifest 是**範本**，不是可以直接 apply 的成品：image、網域、資源上限、
NetworkPolicy 的來源都要換成甲方環境的值。

套用順序：

```bash
kubectl apply -f secret.example.yaml   # 換掉裡面的值，或改用甲方既有的 Secret 管理方式
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
```

## 上線前一定要決定的事

**現在還沒有驗證層。** `MCP_HTTP_ALLOW_UNAUTHENTICATED=yes-i-know` 是刻意要你手動寫上去的，
意思是「我知道任何連得到這個 port 的人都能呼叫所有 Tool，包括會刪資料的」。在補上
Bearer 驗證之前，請務必用 NetworkPolicy 把來源限制在已知的地方機關網段。

**`REQUEST_STATE_SECRET` 是必填的。** 多回合的 Tool（例如 `delete_note`）第一回合與第二回合
不保證落在同一個 Pod；那份狀態是用這把金鑰封的，每個副本必須共用同一把，否則第二回合
會被判成偽造。Server 在非 loopback 的位址上找不到它就會拒絕啟動。

**連線數要算。** `DATABASE_POOL_MAX` × 副本數 不能超過 PostgreSQL 的 `max_connections`
（預設 100）。三個副本配 `DATABASE_POOL_MAX=10` 就是 30 條，還有餘裕給維運。

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

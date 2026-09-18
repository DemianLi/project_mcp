#!/usr/bin/env node
/**
 * HTTP 的進入點。給 k8s 用；開發時用 stdio 的 main.ts。
 *
 * 兩個進入點共用同一個 Server 工廠與同一批 Tool，差別只在誰把訊息送進來。
 *
 * 關機照 k8s 的節奏走，而不是收到 SIGTERM 就倒：kubelet 送 SIGTERM 與 Endpoints 把這個
 * Pod 拿掉是兩件並行的事，沒有先後保證。所以先讓 readiness 轉紅並等一小段時間，讓還在
 * 把流量送進來的 kube-proxy 追上，再停止接受新連線、等在飛的請求做完、收池子。
 */
import { createServer as createHttpServer } from 'node:http';
import { createMcpHandler } from '@modelcontextprotocol/server';
import { createAuthenticator } from './auth/authenticator.js';
import { readAuthConfig } from './auth/config.js';
import { closePool } from './db/pool.js';
import { PROTOCOL_VERSION, SERVER_INFO } from './declarations.js';
import { readHttpConfig, isLoopback } from './http/config.js';
import { createRouter, LIVENESS_PATH, READINESS_PATH } from './http/routes.js';
import { whenIdle } from './lifecycle.js';
import { log } from './log.js';
import { createServer } from './server.js';

// 驗證層先讀：它決定 HTTP 那邊要不要再要求一次「我知道沒有驗證」的宣告。
const auth = readAuthConfig();
if (!auth.ok) {
  log({ event: 'start.refused', reason: auth.reason });
  process.exitCode = 2;
  process.exit(2);
}
const authenticator = createAuthenticator(auth.config);

const configured = readHttpConfig(process.env, auth.config.mode !== 'none');
if (!configured.ok) {
  // 設定不對就不要聽 port。用一行讀得懂的紀錄加一個離開碼收場，而不是丟出例外——
  // 在 k8s 上那會變成一段 stack trace，而讀它的人只需要知道哪個變數不對。
  log({ event: 'start.refused', reason: configured.reason });
  process.exitCode = 2;
  process.exit(2);
}
const config = configured.config;

const handler = createMcpHandler(createServer, {
  // 只講 2026-07-28，和 stdio 那邊同一個決定。
  legacy: 'reject',
  onerror: (error) => {
    log({ event: 'error', message: error.message });
  },
});

let accepting = true;
const router = createRouter({ mcpPath: config.path, handler, accepting: () => accepting, authenticator });
const http = createHttpServer(router);

http.listen(config.port, config.host, () => {
  // 設定成 0 的時候由作業系統挑一個，所以記的是實際綁到的那個，不是設定值。
  const bound = http.address();
  log({
    event: 'start',
    transport: 'http',
    server: SERVER_INFO.name,
    version: SERVER_INFO.version,
    protocol: PROTOCOL_VERSION,
    host: config.host,
    port: typeof bound === 'object' && bound !== null ? bound.port : config.port,
    path: config.path,
    liveness: LIVENESS_PATH,
    readiness: READINESS_PATH,
    auth: authenticator.describe,
  });
  if (isLoopback(config.host)) {
    // 在容器裡這是最容易踩的一個坑：綁 loopback 表示 -p 對映或 Service 都連不進來，
    // 而行程看起來一切正常。講清楚比讓人去猜連線被拒的原因便宜太多。
    log({
      event: 'notice',
      message: `Listening on ${config.host} only — not reachable from outside this host or container. Set MCP_HTTP_HOST=0.0.0.0 to serve externally.`,
    });
  } else if (auth.config.mode === 'none') {
    // 這件事每次啟動都要講一次。它是刻意的，但它不該安靜。
    log({ event: 'warning', message: 'Serving without authentication. Anyone who can reach this port can call every Tool.' });
  }
});

for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.once(signal, () => {
    void stop(signal);
  });
}

let stopping = false;
async function stop(reason: string): Promise<void> {
  if (stopping) {
    return;
  }
  stopping = true;

  // 1. readiness 轉紅，但還在服務。
  accepting = false;
  log({ event: 'stopping', reason, graceMs: config.shutdownGraceMs });
  await new Promise((resolve) => setTimeout(resolve, config.shutdownGraceMs));

  // 2. 不再接受新連線，已經建立的讓它們做完。
  await new Promise<void>((resolve) => http.close(() => resolve()));

  // 3. 等在飛的 Tool 呼叫，再收池子。順序反過來的話，還在跑的查詢會斷在半路。
  await whenIdle();
  await handler.close();
  await closePool();
  log({ event: 'stop', reason });
}

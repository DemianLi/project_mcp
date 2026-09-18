#!/usr/bin/env node
/**
 * 進入點與組裝：把 Server 工廠接到 stdio 上，並負責關機。
 *
 * 協定的機制（讀行、分派、`_meta` 關卡、`resultType`、`ttlMs`）都在
 * `@modelcontextprotocol/server` 裡；這個檔案只決定三件事：講哪一版、怎麼記錄、
 * 什麼時候收手。
 *
 * stdin 關閉就是關機訊號，規格要求 Server 立刻結束。收掉連線池是結束前的最後一件事；
 * 沒開過池子時它什麼都不做。
 */
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import { closePool } from './db/pool.js';
import { PROTOCOL_VERSION, SERVER_INFO } from './declarations.js';
import { whenIdle } from './lifecycle.js';
import { log } from './log.js';
import { createServer } from './server.js';

log({
  event: 'start',
  server: SERVER_INFO.name,
  version: SERVER_INFO.version,
  protocol: PROTOCOL_VERSION,
});

serveStdio(createServer, {
  /**
   * 只講 2026-07-28。
   *
   * 預設值是 `'serve'`：舊版的開場（`initialize`，或任何不帶版本宣告的訊息）會被轉給
   * 同一個工廠做出的舊版實例。本 Server 不做雙版相容，所以明寫 `'reject'`——舊版開場
   * 會收到版本不支援的錯誤，上面列著我們支援的版本，連線保持開著等一個新版開場。
   */
  legacy: 'reject',
  onerror: (error) => {
    log({ event: 'error', message: error.message });
  },
});

let stopping = false;
process.stdin.once('end', () => {
  if (stopping) {
    return;
  }
  stopping = true;
  void stop('stdin closed');
});

/**
 * 關機。
 *
 * 先等在飛的請求做完，再收池子。順序反過來的話，還在跑的查詢會斷在半路。
 *
 * 不主動關 transport：關掉之後 SDK 要送回應會拿到「已關閉」，那一則就永遠送不出去了。
 * stdin 已經結束，沒有東西再撐著事件迴圈，所以剩下的 stdout 寫完行程就自己結束。
 */
async function stop(reason: string): Promise<void> {
  await whenIdle();
  await closePool();
  log({ event: 'stop', reason });
}

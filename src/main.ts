#!/usr/bin/env node
/**
 * 進入點與組裝：把行框架、協定層與 log 接在一起。
 *
 * 逐則序列處理，做完一則才讀下一則。這不是效能選擇而是契約選擇——交錯處理需要
 * 為每則請求各自管理狀態，而無狀態核心的價值正在於沒有那種狀態。
 *
 * stdin 關閉就是關機訊號，規格要求 Server 立刻結束。
 */
import { SERVER_INFO } from './declarations.js';
import { log } from './log.js';
import { handle } from './protocol/handle.js';
import { PROTOCOL_VERSION } from './protocol/versions.js';
import { readLines, writeLine } from './transport/stdio.js';

log({ event: 'start', server: SERVER_INFO.name, version: SERVER_INFO.version, protocol: PROTOCOL_VERSION });

for await (const line of readLines(process.stdin)) {
  const started = Date.now();
  const exchange = await handle(line);
  if (exchange === null) {
    continue;
  }
  if (exchange.response !== undefined) {
    writeLine(process.stdout, exchange.response);
  }
  log({ ...exchange.shape, ms: Date.now() - started });
}

log({ event: 'stop', reason: 'stdin closed' });

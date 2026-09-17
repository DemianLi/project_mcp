#!/usr/bin/env node
/**
 * MCP Server 的進入點。
 *
 * Client 以子行程啟動它，透過 stdin／stdout 對話；stdin 關閉就是關機訊號，
 * 規格要求 Server 立刻結束。
 */
import { serve } from './protocol/stdio.js';
import { log } from './log.js';
import { PROTOCOL_VERSION, SERVER_INFO } from './protocol/versions.js';

log({ event: 'start', server: SERVER_INFO.name, version: SERVER_INFO.version, protocol: PROTOCOL_VERSION });

await serve(process.stdin, process.stdout);

log({ event: 'stop', reason: 'stdin closed' });

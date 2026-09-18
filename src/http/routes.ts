/**
 * 一個請求進來該交給誰。
 *
 * 三條路：MCP 端點、liveness、readiness。其餘 404。健康檢查刻意不走 MCP 那條——
 * 它們不是 MCP 訊息，而且 kubelet 不會講 MCP。
 */
import type { IncomingMessage, ServerResponse } from 'node:http';
import { toNodeHandler } from '@modelcontextprotocol/node';
import type { McpHttpHandler } from '@modelcontextprotocol/server';
import { liveness, readiness, type Health } from './health.js';

export const LIVENESS_PATH = '/healthz';
export const READINESS_PATH = '/readyz';

export interface RouterOptions {
  readonly mcpPath: string;
  readonly handler: McpHttpHandler;
  /** 關機排空期間讀到 false，readiness 就一律答不。 */
  readonly accepting: () => boolean;
}

export function createRouter(
  options: RouterOptions,
): (req: IncomingMessage, res: ServerResponse) => void {
  const mcp = toNodeHandler(options.handler);

  return (req, res) => {
    // url 一定有，但型別上是 optional；沒有 host header 時用一個假的 base 也夠拿 pathname。
    const path = new URL(req.url ?? '/', 'http://placeholder').pathname;

    if (path === LIVENESS_PATH) {
      answer(res, liveness());
      return;
    }

    if (path === READINESS_PATH) {
      if (!options.accepting()) {
        // 關機中。先讓 k8s 把流量移開，在飛的請求才有時間做完。
        answer(res, { ok: false, detail: 'shutting down' });
        return;
      }
      void readiness().then(
        (health) => answer(res, health),
        (cause: unknown) => answer(res, { ok: false, detail: String(cause) }),
      );
      return;
    }

    if (path === options.mcpPath) {
      // 轉接器要的是 method 與 url 一定存在的形狀。Server 端的 IncomingMessage 一定有這兩個，
      // 型別上卻是 optional（同一個型別也用在 Client 端的回應）。這是唯一要斷言的地方。
      void mcp(req as IncomingMessage & { method: string; url: string }, res);
      return;
    }

    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'not found' }));
  };
}

function answer(res: ServerResponse, health: Health): void {
  res.writeHead(health.ok ? 200 : 503, { 'content-type': 'application/json' });
  res.end(JSON.stringify(health));
}

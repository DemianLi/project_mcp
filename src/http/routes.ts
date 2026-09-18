/**
 * 一個請求進來該交給誰。
 *
 * 三條路：MCP 端點、liveness、readiness。其餘 404。健康檢查刻意不走 MCP 那條——
 * 它們不是 MCP 訊息，而且 kubelet 不會講 MCP。
 */
import type { IncomingMessage, ServerResponse } from 'node:http';
import { toNodeHandler } from '@modelcontextprotocol/node';
import type { AuthInfo, McpHttpHandler } from '@modelcontextprotocol/server';
import { audit } from '../audit.js';
import type { Authenticator } from '../auth/authenticator.js';
import { AuthError, type Principal } from '../auth/principal.js';
import { allows } from '../auth/scopes.js';
import { scopeFor } from '../tools/registry.js';
import { liveness, readiness, type Health } from './health.js';

export const LIVENESS_PATH = '/healthz';
export const READINESS_PATH = '/readyz';

export interface RouterOptions {
  readonly mcpPath: string;
  readonly handler: McpHttpHandler;
  /** 關機排空期間讀到 false，readiness 就一律答不。 */
  readonly accepting: () => boolean;
  /** 驗身分。健康檢查不經過它——kubelet 沒有 token，也不該有。 */
  readonly authenticator: Authenticator;
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
      void serveMcp(req, res, options, mcp);
      return;
    }

    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'not found' }));
  };
}

async function serveMcp(
  req: IncomingMessage,
  res: ServerResponse,
  options: RouterOptions,
  mcp: (req: IncomingMessage & { method: string; url: string }, res: ServerResponse) => Promise<void>,
): Promise<void> {
  let principal: Principal;
  try {
    principal = await options.authenticator.authenticate(headersOf(req));
  } catch (cause) {
    refuse(res, cause);
    return;
  }

  // 符合 RFC 6750 的快速拒絕：`Mcp-Name` 已經指名要呼叫哪個 Tool，所以權限不足可以在
  // 分派之前就回 403，而不是讓它跑到一半再回一個成功的錯誤結果。
  //
  // 這不是安全邊界。header 與 body 不一致時是 SDK 在分派前才擋（-32020），所以只信
  // header 等於把授權建在一個下游檢查上。真正的關卡在 `defineTool`，那裡才確定是哪個
  // Tool 要跑；兩邊查的是同一份對照表。
  const named = req.headers['mcp-name'];
  if (typeof named === 'string') {
    const required = scopeFor(named);
    if (!allows(principal.scopes, required)) {
      // 拒絕也要留紀錄。「某機關試圖刪除」正是稽核最需要回答的一種問題，而這條路徑
      // 在分派之前就結束了，Tool 那層的稽核不會跑。
      audit({ agency: principal.agency, tool: named, outcome: 'denied', ms: 0 });
      refuse(res, new AuthError(403, 'insufficient_scope', `Calling ${named} requires the ${required ?? ''} scope.`));
      return;
    }
  }

  // 轉接器把 `req.auth` 原樣當成 `authInfo` 傳進 handler，Tool 那邊就讀得到
  // `ctx.http.authInfo.clientId`。它只負責搬運，不會自己去解 header。
  (req as IncomingMessage & { auth?: AuthInfo }).auth = {
    token: '',
    clientId: principal.agency,
    scopes: [...principal.scopes],
    expiresAt: principal.expiresAt,
  };

  // 轉接器要的是 method 與 url 一定存在的形狀。Server 端的 IncomingMessage 一定有這兩個，
  // 型別上卻是 optional（同一個型別也用在 Client 端的回應）。這是唯一要斷言的地方。
  await mcp(req as IncomingMessage & { method: string; url: string }, res);
}

function headersOf(req: IncomingMessage): Headers {
  const headers = new Headers();
  for (const [name, value] of Object.entries(req.headers)) {
    if (typeof value === 'string') {
      headers.set(name, value);
    } else if (Array.isArray(value)) {
      headers.set(name, value.join(', '));
    }
  }
  return headers;
}

function refuse(res: ServerResponse, cause: unknown): void {
  const error = cause instanceof AuthError ? cause : new AuthError(401, 'invalid_request', 'Unauthenticated.');
  res.writeHead(error.status, {
    'content-type': 'application/json',
    // RFC 6750：401 要說回來的人該怎麼補。細節不寫，那是給攻擊者的提示。
    // 403 不一樣：權限不足時對方已經通過驗證，講清楚缺什麼是幫他去申請，不是洩漏。
    'www-authenticate': `Bearer error="${error.code}"`,
  });
  res.end(JSON.stringify({ error: error.code, detail: error.message }));
}

function answer(res: ServerResponse, health: Health): void {
  res.writeHead(health.ok ? 200 : 503, { 'content-type': 'application/json' });
  res.end(JSON.stringify(health));
}

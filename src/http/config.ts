/**
 * HTTP 進入點的設定，全部從環境變數讀。
 *
 * 純函式，不開 socket——因此「設定對不對」測得起來，不需要真的聽一個 port。
 *
 * 這裡有一條貫穿全檔的規則：**綁 loopback 是開發，綁其他位址是上線**。開發時什麼都
 * 可以省；一旦要對外，缺的東西就不是警告而是拒絕啟動。理由是這兩種情況的代價不對稱：
 * 開發時麻煩一點只是麻煩，上線時少一道驗證就是把服務裸奔在網路上。
 */

export interface HttpConfig {
  /** 聽哪個位址。預設只聽 loopback。 */
  readonly host: string;
  /** 0 表示交給作業系統挑。 */
  readonly port: number;
  /** MCP 端點的路徑。健康檢查走另外兩條。 */
  readonly path: string;
  /** 收到 SIGTERM 之後，先讓 readiness 轉紅多久才開始排空。 */
  readonly shutdownGraceMs: number;
  /** 明知沒有驗證層還要對外服務。只有這個值是 'yes-i-know' 才算數。 */
  readonly allowUnauthenticated: boolean;
}

export type HttpConfigResult =
  | { readonly ok: true; readonly config: HttpConfig }
  | { readonly ok: false; readonly reason: string };

export const HTTP_DEFAULTS = {
  host: '127.0.0.1',
  port: 8080,
  path: '/mcp',
  shutdownGraceMs: 5_000,
} as const;

/** 只認這三個。`0.0.0.0` 與 `::` 是對外，不是 loopback。 */
const LOOPBACK = new Set(['127.0.0.1', '::1', 'localhost']);

export function isLoopback(host: string): boolean {
  return LOOPBACK.has(host);
}

/**
 * @param authenticated 這台是不是設了驗證層。設了的話，對外服務就不必再明寫
 *   `MCP_HTTP_ALLOW_UNAUTHENTICATED`——那個旗標的意思本來就是「我知道現在沒有驗證」。
 */
export function readHttpConfig(
  env: NodeJS.ProcessEnv = process.env,
  authenticated = false,
): HttpConfigResult {
  const host = env['MCP_HTTP_HOST'] ?? HTTP_DEFAULTS.host;

  const port = readNumber(env['MCP_HTTP_PORT'], HTTP_DEFAULTS.port, 'MCP_HTTP_PORT');
  if (!port.ok) {
    return port;
  }
  // 0 是合法的：交給作業系統挑一個。測試與本機開發都靠它避免撞號，啟動時記的是實際綁到的。
  if (port.value > 65_535) {
    return { ok: false, reason: 'MCP_HTTP_PORT must be 0 (pick one) or between 1 and 65535' };
  }

  const path = env['MCP_HTTP_PATH'] ?? HTTP_DEFAULTS.path;
  if (!path.startsWith('/')) {
    return { ok: false, reason: 'MCP_HTTP_PATH must start with /' };
  }

  const grace = readNumber(env['MCP_HTTP_SHUTDOWN_GRACE_MS'], HTTP_DEFAULTS.shutdownGraceMs, 'MCP_HTTP_SHUTDOWN_GRACE_MS');
  if (!grace.ok) {
    return grace;
  }

  const allowUnauthenticated = env['MCP_HTTP_ALLOW_UNAUTHENTICATED'] === 'yes-i-know';

  if (!isLoopback(host)) {
    // 對外服務的兩個前提。兩個都是「少了就會出事」而不是「少了會不方便」。
    if (!authenticated && !allowUnauthenticated) {
      return {
        ok: false,
        reason:
          `Refusing to serve on ${host} with no authentication. ` +
          'Set MCP_HTTP_ALLOW_UNAUTHENTICATED=yes-i-know to do it anyway, or bind 127.0.0.1.',
      };
    }
    const secret = env['REQUEST_STATE_SECRET'];
    if (secret === undefined || Buffer.byteLength(secret) < 32) {
      return {
        ok: false,
        reason:
          `Refusing to serve on ${host} without REQUEST_STATE_SECRET (at least 32 bytes). ` +
          'Every replica must share one, or a multi-round-trip call answered by another replica fails.',
      };
    }
  }

  return {
    ok: true,
    config: { host, port: port.value, path, shutdownGraceMs: grace.value, allowUnauthenticated },
  };
}

function readNumber(
  raw: string | undefined,
  fallback: number,
  name: string,
): { ok: true; value: number } | { ok: false; reason: string } {
  if (raw === undefined || raw.trim() === '') {
    return { ok: true, value: fallback };
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 0) {
    return { ok: false, reason: `${name} must be a non-negative integer, got ${JSON.stringify(raw)}` };
  }
  return { ok: true, value };
}

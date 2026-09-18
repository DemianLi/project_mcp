/**
 * Acceptance 層的 HTTP Client。
 *
 * 啟動真的 `dist/httpMain.js`，聽一個由作業系統挑的 port（`MCP_HTTP_PORT=0`），
 * 從它的 log 讀回實際綁到哪裡。寫死 port 的測試在 CI 上遲早會撞號。
 */
import { spawn, type ChildProcessByStdio } from 'node:child_process';
import type { Readable } from 'node:stream';
import { once } from 'node:events';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { META, type Response } from './client.js';

const HTTP_SERVER = fileURLToPath(new URL('../../dist/httpMain.js', import.meta.url));

export class HttpServer {
  readonly #child: ChildProcessByStdio<null, Readable, Readable>;
  readonly #logs: Record<string, unknown>[] = [];
  #port = 0;

  private constructor(child: ChildProcessByStdio<null, Readable, Readable>) {
    this.#child = child;
  }

  static async start(env: NodeJS.ProcessEnv = {}): Promise<HttpServer> {
    const child = spawn(process.execPath, [HTTP_SERVER], {
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, MCP_HTTP_PORT: '0', MCP_HTTP_SHUTDOWN_GRACE_MS: '0', ...env },
    });
    child.stdout.resume();
    const server = new HttpServer(child);

    const started = new Promise<number>((resolve, reject) => {
      const reader = createInterface({ input: child.stderr });
      reader.on('line', (line: string) => {
        // Server 的 log 是一行一則 JSON，但行程若以其他方式收場，stderr 上也可能出現
        // 別的東西。解不開的就當成雜訊，不要讓它變成測試行程的未捕捉例外。
        let event: { event?: string; port?: number; reason?: string };
        try {
          event = JSON.parse(line) as typeof event;
        } catch {
          return;
        }
        server.#logs.push(event as Record<string, unknown>);
        if (event.event === 'start' && typeof event.port === 'number') {
          resolve(event.port);
        }
        if (event.event === 'start.refused') {
          reject(new Error(event.reason ?? 'refused'));
        }
      });
      child.once('exit', (code) => reject(new Error(`server exited with ${String(code)} before listening`)));
    });

    server.#port = await started;
    return server;
  }

  get origin(): string {
    return `http://127.0.0.1:${this.#port}`;
  }

  /** 送一則 MCP 請求。HTTP binding 要求 header 與 body 講同一件事。 */
  async post(
    id: number,
    method: string,
    params: Record<string, unknown> = {},
    token?: string,
  ): Promise<{ status: number; body: Response }> {
    const headers: Record<string, string> = {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      'Mcp-Method': method,
    };
    const name = params['name'];
    if (typeof name === 'string') {
      headers['Mcp-Name'] = name;
    }
    if (token !== undefined) {
      headers['authorization'] = token;
    }
    const response = await fetch(`${this.origin}/mcp`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ jsonrpc: '2.0', id, method, params: { ...params, _meta: META } }),
    });
    const text = await response.text();
    return {
      status: response.status,
      body: (text === '' ? {} : JSON.parse(text)) as Response,
    };
  }

  /** stderr 上的 log，一行一則 JSON。稽核測試要看它。 */
  get logLines(): readonly Record<string, unknown>[] {
    return this.#logs;
  }

  async get(path: string): Promise<{ status: number; body: Record<string, unknown> }> {
    const response = await fetch(`${this.origin}${path}`);
    return { status: response.status, body: (await response.json()) as Record<string, unknown> };
  }

  /** 送 SIGTERM，等它自己收乾淨。回傳離開碼。 */
  async stop(): Promise<number | null> {
    this.#child.kill('SIGTERM');
    const [code] = (await once(this.#child, 'exit')) as [number | null];
    return code;
  }
}

/**
 * Acceptance 層共用的 Client。
 *
 * 兩種用法：`converse` 一次把所有行送進去再收結果；`Conversation` 一問一答，
 * 因為有些流程的下一句要拿上一句的回應才寫得出來（例如多回合的確認）。
 */
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { once } from 'node:events';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import {
  CLIENT_CAPABILITIES_META_KEY,
  CLIENT_INFO_META_KEY,
  PROTOCOL_VERSION_META_KEY,
} from '@modelcontextprotocol/server';
import { PROTOCOL_VERSION } from '../../src/declarations.js';

export const SERVER = fileURLToPath(new URL('../../dist/main.js', import.meta.url));

/** 每則請求都要帶的 `_meta`。宣告支援 elicitation，多回合的 Tool 才問得出問題。 */
export const META = {
  [PROTOCOL_VERSION_META_KEY]: PROTOCOL_VERSION,
  [CLIENT_CAPABILITIES_META_KEY]: { elicitation: { form: {} } },
  [CLIENT_INFO_META_KEY]: { name: 'wire-test', version: '0.1.0' },
};

export interface Response {
  readonly jsonrpc?: string;
  readonly id?: number | null;
  readonly result?: Record<string, unknown>;
  readonly error?: { code: number; message: string; data?: Record<string, unknown> };
}

export function request(id: number, method: string, params: Record<string, unknown> = {}): string {
  return JSON.stringify({ jsonrpc: '2.0', id, method, params: { ...params, _meta: META } });
}

/** 啟動一個 Server，送進這些行，收集 stdout 與 stderr，然後關掉 stdin。 */
export async function converse(
  lines: readonly string[],
  env: NodeJS.ProcessEnv = {},
): Promise<{ stdout: string; stderr: string; code: number | null }> {
  const child = spawn(process.execPath, [SERVER], {
    stdio: ['pipe', 'pipe', 'pipe'],
    env: { ...process.env, ...env },
  });
  let stdout = '';
  let stderr = '';
  child.stdout.setEncoding('utf8').on('data', (chunk: string) => { stdout += chunk; });
  child.stderr.setEncoding('utf8').on('data', (chunk: string) => { stderr += chunk; });

  for (const line of lines) {
    child.stdin.write(`${line}\n`);
  }
  child.stdin.end();

  const [code] = (await once(child, 'exit')) as [number | null];
  return { stdout, stderr, code };
}

export function responses(stdout: string): Response[] {
  return stdout
    .split('\n')
    .filter((line) => line !== '')
    .map((line) => JSON.parse(line) as Response);
}

/** 一問一答：送一則請求，等它的回應。有些流程的下一句要拿上一句的回應才寫得出來。 */
export class Conversation {
  readonly #child: ChildProcessWithoutNullStreams;
  readonly #pending = new Map<number, (response: Response) => void>();
  #nextId = 1;

  constructor(env: NodeJS.ProcessEnv = {}) {
    this.#child = spawn(process.execPath, [SERVER], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, ...env },
    });
    // stderr 是 log，測試不看，但要讀掉才不會把管子塞住。
    this.#child.stderr.resume();
    createInterface({ input: this.#child.stdout }).on('line', (line: string) => {
      const response = JSON.parse(line) as Response;
      if (typeof response.id !== 'number') {
        return;
      }
      const waiting = this.#pending.get(response.id);
      if (waiting !== undefined) {
        this.#pending.delete(response.id);
        waiting(response);
      }
    });
  }

  async ask(method: string, params: Record<string, unknown> = {}): Promise<Response> {
    const id = this.#nextId;
    this.#nextId += 1;
    const answered = new Promise<Response>((resolve) => this.#pending.set(id, resolve));
    this.#child.stdin.write(`${request(id, method, params)}\n`);
    return answered;
  }

  async close(): Promise<void> {
    this.#child.stdin.end();
    await once(this.#child, 'exit');
  }
}

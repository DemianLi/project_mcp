/**
 * Acceptance 層：把 Server 當成 Client 會看到的樣子來測。
 *
 * 跨過 wire 邊界——真的啟動一個子行程，真的在 stdin／stdout 上講 JSON-RPC。
 * 協定的實作是 `@modelcontextprotocol/server` 的，所以這一層測的不是它對不對，而是
 * 「我們把它接成了什麼」：講哪一版、菜單上有誰、清單多久算新鮮、log 有沒有汙染協定通道。
 */
import { deepStrictEqual, match, ok, strictEqual } from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  CLIENT_CAPABILITIES_META_KEY,
  PROTOCOL_VERSION_META_KEY,
} from '@modelcontextprotocol/server';
import { PROTOCOL_VERSION } from '../src/declarations.js';

const SERVER = fileURLToPath(new URL('../dist/main.js', import.meta.url));
const META = {
  [PROTOCOL_VERSION_META_KEY]: PROTOCOL_VERSION,
  [CLIENT_CAPABILITIES_META_KEY]: {},
};

/** 啟動一個 Server，送進這些行，收集 stdout 與 stderr，然後關掉 stdin。 */
async function converse(lines: readonly string[]): Promise<{
  stdout: string;
  stderr: string;
  code: number | null;
}> {
  const child = spawn(process.execPath, [SERVER], { stdio: ['pipe', 'pipe', 'pipe'] });
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

function responses(stdout: string): Record<string, unknown>[] {
  return stdout
    .split('\n')
    .filter((line) => line !== '')
    .map((line) => JSON.parse(line) as Record<string, unknown>);
}

function call(id: number, method: string, params: Record<string, unknown> = {}): string {
  return JSON.stringify({ jsonrpc: '2.0', id, method, params: { ...params, _meta: META } });
}

function resultOf(stdout: string, index = 0): Record<string, unknown> {
  const response = responses(stdout)[index] as { result?: Record<string, unknown> };
  ok(response.result !== undefined, `expected a result, got ${JSON.stringify(response)}`);
  return response.result;
}

function errorOf(stdout: string, index = 0): { code: number; data?: Record<string, unknown> } {
  const response = responses(stdout)[index] as { error?: { code: number; data?: Record<string, unknown> } };
  ok(response.error !== undefined, `expected an error, got ${JSON.stringify(response)}`);
  return response.error;
}

describe('over the wire', () => {
  let transcript: { stdout: string; stderr: string; code: number | null };

  before(async () => {
    transcript = await converse([
      call(1, 'server/discover'),
      call(2, 'tools/list'),
      call(3, 'tools/call', { name: 'get_weather', arguments: { city: 'Taipei' } }),
      JSON.stringify({ jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: 2, _meta: META } }),
    ]);
  });

  it('exits cleanly when stdin closes', () => {
    strictEqual(transcript.code, 0);
  });

  it('answers each request once, and says nothing back to a notification', () => {
    strictEqual(responses(transcript.stdout).length, 3);
  });

  it('keeps every response on its own line', () => {
    ok(transcript.stdout.endsWith('\n'));
    for (const line of transcript.stdout.split('\n').filter((l) => l !== '')) {
      JSON.parse(line);
    }
  });

  it('answers in the order it was asked', () => {
    deepStrictEqual(responses(transcript.stdout).map((r) => r['id']), [1, 2, 3]);
  });

  it('discovers without a handshake, speaking the revision this repo is written against', () => {
    deepStrictEqual(resultOf(transcript.stdout)['supportedVersions'], [PROTOCOL_VERSION]);
  });

  it('declares tools and nothing else', () => {
    deepStrictEqual(Object.keys(resultOf(transcript.stdout)['capabilities'] as object), ['tools']);
  });

  it('marks every result complete', () => {
    for (const response of responses(transcript.stdout)) {
      strictEqual((response['result'] as Record<string, unknown>)['resultType'], 'complete');
    }
  });

  it('gives the menu a cache lifetime, not the conservative default', () => {
    const list = resultOf(transcript.stdout, 1);
    strictEqual(list['ttlMs'], 60_000);
    strictEqual(list['cacheScope'], 'private');
  });

  it('lists the tools this server registered', () => {
    const tools = resultOf(transcript.stdout, 1)['tools'] as { name: string }[];
    deepStrictEqual(tools.map((tool) => tool.name), ['get_weather']);
  });

  it('runs a tool end to end, saying the same thing to the model and to the program', () => {
    const result = resultOf(transcript.stdout, 2);
    strictEqual(result['isError'], false);
    const content = result['content'] as { text: string }[];
    deepStrictEqual(JSON.parse(content[0]!.text), result['structuredContent']);
  });

  it('writes its log to stderr, never to the protocol channel', () => {
    match(transcript.stderr, /"event":"start"/);
    match(transcript.stderr, /"event":"stop"/);
    for (const response of responses(transcript.stdout)) {
      strictEqual(response['jsonrpc'], '2.0');
    }
  });
});

describe('over the wire, when the client gets it wrong', () => {
  it('refuses a Legacy handshake with the version error, not a crash', async () => {
    const { stdout } = await converse([
      JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'initialize',
        params: { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'legacy', version: '1' } },
      }),
    ]);
    const error = errorOf(stdout);
    strictEqual(error.code, -32022);
    deepStrictEqual(error.data?.['supported'], [PROTOCOL_VERSION]);
  });

  it('refuses a request that names no version at all', async () => {
    const { stdout } = await converse([JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'server/discover' })]);
    strictEqual(errorOf(stdout).code, -32022);
  });

  it('calls an unknown tool a bad parameter, not a missing method', async () => {
    const { stdout } = await converse([call(1, 'tools/call', { name: 'nope' })]);
    strictEqual(errorOf(stdout).code, -32602);
  });

  it('lets the model see a rejected argument, instead of failing the call', async () => {
    const { stdout } = await converse([call(1, 'tools/call', { name: 'get_weather', arguments: {} })]);
    strictEqual(resultOf(stdout)['isError'], true);
  });

  it('survives a bad line and keeps answering the next one', async () => {
    const { stdout } = await converse(['{ broken', call(9, 'tools/list')]);
    deepStrictEqual(responses(stdout).map((r) => r['id']), [9]);
  });

  it('opens no database connection just by starting up and running a tool', async () => {
    const { stderr } = await converse([
      call(1, 'tools/call', { name: 'get_weather', arguments: { city: 'Taipei' } }),
    ]);
    strictEqual(stderr.includes('db.poolOpened'), false);
  });
});

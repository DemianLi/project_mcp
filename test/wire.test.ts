/**
 * Acceptance 層：把 Server 當成 Client 會看到的樣子來測。
 *
 * 跨過 wire 邊界——真的啟動一個子行程，真的在 stdin／stdout 上講 JSON-RPC。
 * 有些規則只能在這裡測：stdout 是否乾淨、stdin 關閉會不會結束、一行一則的框架。
 */
import { deepStrictEqual, match, ok, strictEqual } from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const SERVER = fileURLToPath(new URL('../dist/main.js', import.meta.url));
const PROTOCOL_VERSION = '2026-07-28';
const META = {
  'io.modelcontextprotocol/protocolVersion': PROTOCOL_VERSION,
  'io.modelcontextprotocol/clientCapabilities': {},
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

describe('over the wire', () => {
  let transcript: { stdout: string; stderr: string; code: number | null };

  before(async () => {
    transcript = await converse([
      JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'server/discover' }),
      call(2, 'tools/list'),
      call(3, 'tools/call', { name: 'nope' }),
      JSON.stringify({ jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: 2 } }),
      'this is not JSON',
    ]);
  });

  it('exits cleanly when stdin closes', () => {
    strictEqual(transcript.code, 0);
  });

  it('answers each request once, and says nothing back to a notification', () => {
    strictEqual(responses(transcript.stdout).length, 4);
  });

  it('keeps every response on its own line', () => {
    ok(transcript.stdout.endsWith('\n'));
    for (const line of transcript.stdout.split('\n').filter((l) => l !== '')) {
      ok(!line.includes('\n'));
      JSON.parse(line);
    }
  });

  it('answers in the order it was asked', () => {
    deepStrictEqual(responses(transcript.stdout).map((r) => r['id']), [1, 2, 3, null]);
  });

  it('discovers without a handshake', () => {
    const discover = responses(transcript.stdout)[0] as { result: Record<string, unknown> };
    deepStrictEqual(discover.result['supportedVersions'], [PROTOCOL_VERSION]);
  });

  it('marks every result complete', () => {
    for (const response of responses(transcript.stdout)) {
      if ('result' in response) {
        strictEqual((response['result'] as Record<string, unknown>)['resultType'], 'complete');
      }
    }
  });

  it('writes its log to stderr, never to the protocol channel', () => {
    match(transcript.stderr, /"event":"start"/);
    match(transcript.stderr, /"event":"stop"/);
    for (const response of responses(transcript.stdout)) {
      strictEqual(response['jsonrpc'], '2.0');
    }
  });

  it('refuses a Legacy handshake with the version error, not a crash', async () => {
    const { stdout } = await converse([
      JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { _meta: {
        'io.modelcontextprotocol/protocolVersion': '2025-11-25',
        'io.modelcontextprotocol/clientCapabilities': {},
      } } }),
    ]);
    const [response] = responses(stdout) as [{ error: { code: number; data: unknown } }];
    strictEqual(response.error.code, -32022);
    deepStrictEqual(response.error.data, { supportedVersions: [PROTOCOL_VERSION] });
  });

  it('runs a tool end to end and answers on one line', async () => {
    const { stdout } = await converse([
      call(1, 'tools/call', { name: 'get_weather', arguments: { city: 'Taipei' } }),
    ]);
    const [response] = responses(stdout) as [{ result: Record<string, unknown> }];
    strictEqual(response.result['resultType'], 'complete');
    strictEqual(response.result['isError'], false);
    const content = response.result['content'] as { text: string }[];
    deepStrictEqual(JSON.parse(content[0]!.text), response.result['structuredContent']);
  });

  it('opens no database connection just by starting up and running a tool', async () => {
    const { stderr } = await converse([
      call(1, 'tools/call', { name: 'get_weather', arguments: { city: 'Taipei' } }),
    ]);
    strictEqual(stderr.includes('db.poolOpened'), false);
  });

  it('survives a bad line and keeps answering the next one', async () => {
    const { stdout } = await converse(['{ broken', call(9, 'tools/list')]);
    deepStrictEqual(responses(stdout).map((r) => r['id']), [null, 9]);
  });
});

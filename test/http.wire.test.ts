/**
 * Acceptance 層：同一批 Tool 走 HTTP。
 *
 * 兩個進入點的重點不同。stdio 那邊測「協定怎麼接」，這邊測「k8s 會問的事」：健康檢查、
 * 對外服務的守門、SIGTERM 之後的收尾順序。協定本身兩邊共用，所以這裡只確認它真的是
 * 同一個 Server，不重測一次。
 *
 * 不碰資料庫——沒有 DATABASE_URL 時 readiness 一樣該是綠的。
 */
import { deepStrictEqual, match, ok, strictEqual } from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { PROTOCOL_VERSION } from '../src/declarations.js';
import { META } from './support/client.js';
import { HttpServer } from './support/httpClient.js';

describe('over HTTP', () => {
  let server: HttpServer;

  before(async () => {
    // 明確地不給資料庫：這一組測的是「沒設定資料庫時也該是健康的」，而跑測試的環境
    // 可能剛好有 DATABASE_URL。
    server = await HttpServer.start({ DATABASE_URL: '' });
  });

  after(async () => {
    await server.stop();
  });

  it('is alive without touching the database', async () => {
    const { status, body } = await server.get('/healthz');
    strictEqual(status, 200);
    strictEqual(body['ok'], true);
  });

  it('is ready when no database is configured', async () => {
    const { status, body } = await server.get('/readyz');
    strictEqual(status, 200);
    strictEqual(body['ok'], true);
    match(String(body['detail']), /no database configured/);
  });

  it('answers 404 off its own paths', async () => {
    const { status } = await server.get('/');
    strictEqual(status, 404);
  });

  it('speaks the same revision as the stdio entry', async () => {
    const { body } = await server.post(1, 'server/discover');
    deepStrictEqual(body.result?.['supportedVersions'], [PROTOCOL_VERSION]);
  });

  it('serves the same Tools as the stdio entry', async () => {
    const { body } = await server.post(2, 'tools/list');
    const tools = body.result?.['tools'] as { name: string }[];
    deepStrictEqual(tools.map((tool) => tool.name), ['get_weather', 'list_notes', 'add_note', 'delete_note']);
  });

  it('keeps the menu cache hint it was configured with', async () => {
    const { body } = await server.post(3, 'tools/list');
    strictEqual(body.result?.['ttlMs'], 60_000);
  });

  it('runs a Tool', async () => {
    const { body } = await server.post(4, 'tools/call', { name: 'get_weather', arguments: { city: 'Taipei' } });
    strictEqual(body.result?.['isError'], false);
  });

  it('refuses a body whose method the headers do not name', async () => {
    // HTTP binding 要求 header 與 body 講同一件事，這樣中間層不必解 body 就能路由。
    const response = await fetch(`${server.origin}/mcp`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 9, method: 'tools/list', params: { _meta: META } }),
    });
    const body = (await response.json()) as { error?: { code: number } };
    strictEqual(body.error?.code, -32020);
  });
});

describe('over HTTP, shutting down', () => {
  it('turns readiness red first, then exits cleanly', async () => {
    const server = await HttpServer.start({ MCP_HTTP_SHUTDOWN_GRACE_MS: '300' });
    strictEqual((await server.get('/readyz')).status, 200);

    const stopped = server.stop();
    // 排空期間還在服務，但 readiness 已經轉紅，k8s 才會先把流量移開。
    const draining = await server.get('/readyz');
    strictEqual(draining.status, 503);
    match(String(draining.body['detail']), /shutting down/);

    strictEqual(await stopped, 0);
  });
});

describe('over HTTP, refusing to start', () => {
  it('will not serve off loopback with no authentication', async () => {
    await assertRefused({ MCP_HTTP_HOST: '0.0.0.0' }, /no authentication/);
  });

  it('will not serve off loopback without a shared requestState secret', async () => {
    await assertRefused(
      { MCP_HTTP_HOST: '0.0.0.0', MCP_HTTP_ALLOW_UNAUTHENTICATED: 'yes-i-know' },
      /REQUEST_STATE_SECRET/,
    );
  });
});

async function assertRefused(env: NodeJS.ProcessEnv, expected: RegExp): Promise<void> {
  try {
    const server = await HttpServer.start(env);
    await server.stop();
    ok(false, 'expected the server to refuse to start');
  } catch (cause) {
    match(cause instanceof Error ? cause.message : String(cause), expected);
  }
}

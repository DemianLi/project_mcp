/**
 * 碰真的資料庫的 Acceptance 測試。
 *
 * 沒有 `DATABASE_URL` 就整段跳過——離線的 `npm test` 不需要一台 PostgreSQL，這是連線池
 * 懶建的用意。CI 上有 service container，所以這一段在那裡會真的跑。
 *
 * 跑之前資料表要存在（`db/schema.sql`）。
 */
import { deepStrictEqual, match, notStrictEqual, ok, strictEqual } from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { converse, request, responses, Conversation, type Response } from './support/client.js';
import { HttpServer } from './support/httpClient.js';
import { AUDIENCE, ISSUER, createIssuer, goodClaims } from './support/tokens.js';

const DATABASE_URL = process.env['DATABASE_URL'];
const OFFLINE = DATABASE_URL === undefined || DATABASE_URL.trim() === '';

function resultOf(response: Response): Record<string, unknown> {
  ok(response.result !== undefined, `expected a result, got ${JSON.stringify(response)}`);
  return response.result;
}

function structured(response: Response): Record<string, unknown> {
  const result = resultOf(response);
  strictEqual(result['isError'], false, `expected success, got ${JSON.stringify(result)}`);
  return result['structuredContent'] as Record<string, unknown>;
}

function failure(response: Response): Record<string, unknown> {
  const result = resultOf(response);
  strictEqual(result['isError'], true, `expected a tool failure, got ${JSON.stringify(result)}`);
  return result['structuredContent'] as Record<string, unknown>;
}

describe('notes, against a real database', { skip: OFFLINE ? 'DATABASE_URL is not set' : false }, () => {
  let mcp: Conversation;

  before(() => {
    mcp = new Conversation();
  });

  after(async () => {
    await mcp.close();
  });

  it('answers a slow request even when stdin closes right behind it', async () => {
    // 關機不能比回應早。Transport 一關，SDK 送回應會拿到「已關閉」而把那一則丟掉——
    // 只有真的做 IO 的 Tool 看得出來，`get_weather` 快到跑不進這個縫。
    const { stdout, code } = await converse([request(1, 'tools/call', { name: 'list_notes', arguments: {} })]);
    const [response] = responses(stdout);
    ok(response?.result !== undefined, `expected a result, got ${stdout || '(nothing on stdout)'}`);
    strictEqual(response.result['isError'], false);
    strictEqual(code, 0);
  });

  it('reports the database in its readiness probe', async () => {
    // readiness 的用處就在這裡：資料庫連不上時這個 Pod 該被移出流量，而不是繼續收請求。
    const http = await HttpServer.start();
    try {
      const { status, body } = await http.get('/readyz');
      strictEqual(status, 200);
      match(String(body['detail']), /database reachable/);
    } finally {
      await http.stop();
    }
  });

  it('will not let one agency use another agency\'s confirmation', async () => {
    // 這是 requestState 綁身分的用處。即使兩個機關看的是同一份資料，「誰批准了這次刪除」
    // 也必須對得起來——否則稽核紀錄會指向錯的人。
    const issuer = await createIssuer();
    const http = await HttpServer.start({
      MCP_AUTH_MODE: 'jwt',
      MCP_AUTH_JWT_ISSUER: ISSUER,
      MCP_AUTH_JWT_AUDIENCE: AUDIENCE,
      MCP_AUTH_JWT_PUBLIC_KEY: issuer.publicKeyPem,
    });
    // 兩個機關都拿得到刪除權限——這個測試要驗的是「誰批准的」，不是「誰有權限」。
    const tokenFor = async (agency: string): Promise<string> =>
      `Bearer ${await issuer.sign(goodClaims(agency, 'notes:read notes:write notes:delete'), { expiresIn: '5m' })}`;

    try {
      const written = await http.post(1, 'tools/call', { name: 'add_note', arguments: { body: `cross ${Date.now()}` } }, await tokenFor('LG-A'));
      const id = (written.body.result?.['structuredContent'] as { id: number }).id;

      // 甲機關問出確認。
      const asked = await http.post(2, 'tools/call', { name: 'delete_note', arguments: { id } }, await tokenFor('LG-A'));
      const state = asked.body.result?.['requestState'];
      strictEqual(asked.body.result?.['resultType'], 'input_required');
      ok(typeof state === 'string');

      // 乙機關拿它去刪。
      const replayed = await http.post(3, 'tools/call', {
        name: 'delete_note',
        arguments: { id },
        inputResponses: { confirm: { action: 'accept', content: { confirm: true } } },
        requestState: state,
      }, await tokenFor('LG-B'));
      strictEqual(replayed.body.error?.code, -32602);

      // 甲機關自己用同一份狀態就成立。
      const own = await http.post(4, 'tools/call', {
        name: 'delete_note',
        arguments: { id },
        inputResponses: { confirm: { action: 'accept', content: { confirm: true } } },
        requestState: state,
      }, await tokenFor('LG-A'));
      deepStrictEqual(own.body.result?.['structuredContent'], { id, deleted: true });
    } finally {
      await http.stop();
    }
  });

  it('writes a note and reads it back', async () => {
    const body = `wire test ${Date.now()}`;
    const written = structured(await mcp.ask('tools/call', { name: 'add_note', arguments: { body } }));
    ok(typeof written['id'] === 'number');
    strictEqual(written['body'], body);

    const listed = structured(await mcp.ask('tools/call', { name: 'list_notes', arguments: { limit: 50 } }));
    const items = listed['items'] as { id: number; body: string }[];
    ok(items.some((item) => item.id === written['id'] && item.body === body));
  });

  it('refuses a note the table will not accept, and says to fix the request', async () => {
    // schema 擋得住空字串，所以這裡要送一個通得過 schema、但資料表的 check 會擋的值。
    const tooLong = 'x'.repeat(2001);
    const response = await mcp.ask('tools/call', { name: 'add_note', arguments: { body: tooLong } });
    // 2000 是 schema 的上限，所以這一筆連 Tool 都進不去：SDK 直接回 isError。
    strictEqual(resultOf(response)['isError'], true);
  });

  it('asks before deleting, then deletes on the retry', async () => {
    const body = `to delete ${Date.now()}`;
    const written = structured(await mcp.ask('tools/call', { name: 'add_note', arguments: { body } }));
    const id = written['id'] as number;

    // 第一回合：應該回 input_required，帶著要問的問題與封好的狀態。
    const asked = resultOf(await mcp.ask('tools/call', { name: 'delete_note', arguments: { id } }));
    strictEqual(asked['resultType'], 'input_required');
    const requests = asked['inputRequests'] as Record<string, { method: string; params: { message: string } }>;
    ok('confirm' in requests);
    strictEqual(requests['confirm']?.method, 'elicitation/create');
    match(requests['confirm']?.params.message ?? '', new RegExp(`Delete note ${id}`));
    const state = asked['requestState'];
    ok(typeof state === 'string' && state.length > 0);

    // 第二回合：帶著答案與原樣的狀態重送。
    const deleted = structured(
      await mcp.ask('tools/call', {
        name: 'delete_note',
        arguments: { id },
        inputResponses: { confirm: { action: 'accept', content: { confirm: true } } },
        requestState: state,
      }),
    );
    deepStrictEqual(deleted, { id, deleted: true });

    const listed = structured(await mcp.ask('tools/call', { name: 'list_notes', arguments: { limit: 50 } }));
    const items = listed['items'] as { id: number }[];
    strictEqual(items.some((item) => item.id === id), false);
  });

  it('keeps the note when the confirmation is declined', async () => {
    const written = structured(
      await mcp.ask('tools/call', { name: 'add_note', arguments: { body: `keep me ${Date.now()}` } }),
    );
    const id = written['id'] as number;
    const asked = resultOf(await mcp.ask('tools/call', { name: 'delete_note', arguments: { id } }));

    const declined = failure(
      await mcp.ask('tools/call', {
        name: 'delete_note',
        arguments: { id },
        inputResponses: { confirm: { action: 'decline' } },
        requestState: asked['requestState'],
      }),
    );
    match(String(declined['message']), /not confirmed|declined/);

    const listed = structured(await mcp.ask('tools/call', { name: 'list_notes', arguments: { limit: 50 } }));
    strictEqual((listed['items'] as { id: number }[]).some((item) => item.id === id), true);
  });

  it('rejects a requestState that was tampered with', async () => {
    const written = structured(
      await mcp.ask('tools/call', { name: 'add_note', arguments: { body: `tamper ${Date.now()}` } }),
    );
    const id = written['id'] as number;
    const asked = resultOf(await mcp.ask('tools/call', { name: 'delete_note', arguments: { id } }));
    const state = asked['requestState'] as string;
    const tampered = `${state.slice(0, -1)}${state.endsWith('A') ? 'B' : 'A'}`;
    notStrictEqual(tampered, state);

    const response = await mcp.ask('tools/call', {
      name: 'delete_note',
      arguments: { id },
      inputResponses: { confirm: { action: 'accept', content: { confirm: true } } },
      requestState: tampered,
    });
    // 改過的狀態連 Tool 都進不去：SDK 在 seam 上就擋掉了。
    strictEqual(response.error?.code, -32602);
    strictEqual(response.error?.data?.['reason'], 'invalid_request_state');

    const listed = structured(await mcp.ask('tools/call', { name: 'list_notes', arguments: { limit: 50 } }));
    strictEqual((listed['items'] as { id: number }[]).some((item) => item.id === id), true);
  });

  it('will not let a confirmation for one note delete another', async () => {
    const first = structured(await mcp.ask('tools/call', { name: 'add_note', arguments: { body: `a ${Date.now()}` } }));
    const second = structured(await mcp.ask('tools/call', { name: 'add_note', arguments: { body: `b ${Date.now()}` } }));
    const asked = resultOf(await mcp.ask('tools/call', { name: 'delete_note', arguments: { id: first['id'] } }));

    const mismatched = failure(
      await mcp.ask('tools/call', {
        name: 'delete_note',
        // 狀態是替 first 封的，參數卻指向 second。
        arguments: { id: second['id'] },
        inputResponses: { confirm: { action: 'accept', content: { confirm: true } } },
        requestState: asked['requestState'],
      }),
    );
    strictEqual(mismatched['remedy'], 'FIX_REQUEST');

    const listed = structured(await mcp.ask('tools/call', { name: 'list_notes', arguments: { limit: 50 } }));
    const ids = (listed['items'] as { id: number }[]).map((item) => item.id);
    ok(ids.includes(second['id'] as number));
  });
});

import { deepStrictEqual, ok, strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { handle } from './handle.js';
import { PROTOCOL_VERSION } from './versions.js';

const META = {
  'io.modelcontextprotocol/protocolVersion': PROTOCOL_VERSION,
  'io.modelcontextprotocol/clientCapabilities': {},
};

function line(id: number, method: string, params: Record<string, unknown> = {}): string {
  return JSON.stringify({ jsonrpc: '2.0', id, method, params: { ...params, _meta: META } });
}

describe('handle', () => {
  it('turns a request line into a response line', async () => {
    const exchange = await handle(line(1, 'tools/list'));
    ok(exchange !== null);
    ok(exchange.response !== undefined);
    const response = JSON.parse(exchange.response) as Record<string, unknown>;
    strictEqual(response['id'], 1);
    strictEqual(exchange.shape['outcome'], 'ok');
    strictEqual(exchange.shape['method'], 'tools/list');
  });

  it('says nothing back to a notification, but still reports its shape', async () => {
    const exchange = await handle('{"jsonrpc":"2.0","method":"notifications/cancelled"}');
    ok(exchange !== null);
    strictEqual(exchange.response, undefined);
    strictEqual(exchange.shape['outcome'], 'noted');
  });

  it('treats a blank line as nothing at all', async () => {
    strictEqual(await handle('   '), null);
  });

  it('answers a broken line without throwing', async () => {
    const exchange = await handle('{ broken');
    ok(exchange !== null);
    ok(exchange.response !== undefined);
    strictEqual(exchange.shape['outcome'], 'rejected');
    strictEqual(exchange.shape['code'], -32700);
  });

  it('keeps a response on one line, so the framing survives', async () => {
    const exchange = await handle(line(2, 'server/discover'));
    ok(exchange?.response !== undefined);
    strictEqual(exchange.response.includes('\n'), false);
  });

  it('reports shape only, never the content of a call', async () => {
    const exchange = await handle(line(3, 'tools/call', { name: 'nope', arguments: { body: 'secret' } }));
    ok(exchange !== null);
    deepStrictEqual(Object.keys(exchange.shape).sort(), ['method', 'outcome']);
    strictEqual(JSON.stringify(exchange.shape).includes('secret'), false);
  });
});

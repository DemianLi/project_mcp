import { deepStrictEqual, ok, strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { ErrorCode } from './errors.js';
import type { JsonRpcRequest } from './messages.js';
import { route } from './router.js';
import { PROTOCOL_VERSION } from './versions.js';

const META = {
  'io.modelcontextprotocol/protocolVersion': PROTOCOL_VERSION,
  'io.modelcontextprotocol/clientCapabilities': {},
};

function request(method: string, params: Record<string, unknown> = {}): JsonRpcRequest {
  return { jsonrpc: '2.0', id: 1, method, params: { ...params, _meta: META } };
}

describe('route', () => {
  it('answers server/discover without any _meta, because that is what it is for', async () => {
    const response = await route({ jsonrpc: '2.0', id: 1, method: 'server/discover' });
    ok('result' in response);
    strictEqual(response.result['resultType'], 'complete');
    deepStrictEqual(response.result['supportedVersions'], [PROTOCOL_VERSION]);
  });

  it('declares no Resources, only tools', async () => {
    const response = await route({ jsonrpc: '2.0', id: 1, method: 'server/discover' });
    ok('result' in response);
    deepStrictEqual(Object.keys(response.result['capabilities'] as object), ['tools']);
  });

  it('lists tools with the cache hints a list result must carry', async () => {
    const response = await route(request('tools/list'));
    ok('result' in response);
    strictEqual(response.result['resultType'], 'complete');
    deepStrictEqual(response.result['tools'], []);
    strictEqual(typeof response.result['ttlMs'], 'number');
    strictEqual(response.result['cacheScope'], 'private');
  });

  it('refuses a request whose _meta is missing', async () => {
    const response = await route({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
    ok('error' in response);
    strictEqual(response.error.code, ErrorCode.InvalidParams);
  });

  it('calls an unknown tool a parameter error, since tools/call itself exists', async () => {
    const response = await route(request('tools/call', { name: 'get_issue' }));
    ok('error' in response);
    strictEqual(response.error.code, ErrorCode.InvalidParams);
  });

  it('refuses tools/call with no name', async () => {
    const response = await route(request('tools/call'));
    ok('error' in response);
    strictEqual(response.error.code, ErrorCode.InvalidParams);
  });

  it('answers an unknown method with -32601', async () => {
    const response = await route(request('initialize'));
    ok('error' in response);
    strictEqual(response.error.code, ErrorCode.MethodNotFound);
  });

  it('checks the version before the method, so a Legacy Client hears about the version', async () => {
    const response = await route({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        _meta: {
          'io.modelcontextprotocol/protocolVersion': '2025-11-25',
          'io.modelcontextprotocol/clientCapabilities': {},
        },
      },
    });
    ok('error' in response);
    strictEqual(response.error.code, ErrorCode.UnsupportedProtocolVersion);
  });
});

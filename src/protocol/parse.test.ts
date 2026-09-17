import { deepStrictEqual, strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { ErrorCode } from './errors.js';
import { parse } from './parse.js';

describe('parse', () => {
  it('reads a request', () => {
    const parsed = parse('{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"a":1}}');
    strictEqual(parsed.kind, 'request');
    if (parsed.kind !== 'request') return;
    strictEqual(parsed.request.id, 1);
    strictEqual(parsed.request.method, 'tools/list');
    deepStrictEqual(parsed.request.params, { a: 1 });
  });

  it('reads a request with no params', () => {
    const parsed = parse('{"jsonrpc":"2.0","id":"a","method":"server/discover"}');
    strictEqual(parsed.kind, 'request');
    if (parsed.kind !== 'request') return;
    strictEqual(parsed.request.params, undefined);
  });

  it('reads a notification as a notification, not a request', () => {
    strictEqual(parse('{"jsonrpc":"2.0","method":"notifications/cancelled"}').kind, 'notification');
  });

  it('reports a parse error with a null id, because no id could be read', () => {
    const parsed = parse('{ not json');
    strictEqual(parsed.kind, 'error');
    if (parsed.kind !== 'error') return;
    strictEqual(parsed.response.error.code, ErrorCode.ParseError);
    strictEqual(parsed.response.id, null);
  });

  it('keeps the id on an invalid request, so the caller can match it up', () => {
    const parsed = parse('{"jsonrpc":"1.0","id":7,"method":"tools/list"}');
    strictEqual(parsed.kind, 'error');
    if (parsed.kind !== 'error') return;
    strictEqual(parsed.response.error.code, ErrorCode.InvalidRequest);
    strictEqual(parsed.response.id, 7);
  });

  it('rejects a message with no method', () => {
    const parsed = parse('{"jsonrpc":"2.0","id":1}');
    strictEqual(parsed.kind, 'error');
    if (parsed.kind !== 'error') return;
    strictEqual(parsed.response.error.code, ErrorCode.InvalidRequest);
  });

  it('rejects array params, which MCP never sends', () => {
    strictEqual(parse('{"jsonrpc":"2.0","id":1,"method":"tools/list","params":[1,2]}').kind, 'error');
  });

  it('rejects a batch', () => {
    const parsed = parse('[{"jsonrpc":"2.0","id":1,"method":"tools/list"}]');
    strictEqual(parsed.kind, 'error');
    if (parsed.kind !== 'error') return;
    strictEqual(parsed.response.error.code, ErrorCode.InvalidRequest);
  });
});

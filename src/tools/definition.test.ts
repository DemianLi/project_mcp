/**
 * `defineTool` 包起來的那幾件事：授權、稽核、多回合結果的透傳。
 *
 * 直接抓出註冊上去的 handler 來測，因為授權那一段在 HTTP 上碰不到（見 auth/scopes.ts），
 * 沒有這個測試它就是一段沒被驗過的程式碼。
 */
import { ok, strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import type { McpServer, ServerContext } from '@modelcontextprotocol/server';
import { z } from 'zod';
import { Scope } from '../auth/scopes.js';
import { defineTool } from './definition.js';
import { ok as success } from './result.js';

type Handler = (args: { value: string }, ctx: ServerContext) => Promise<Record<string, unknown>>;

/** 假的 Server，只為了把註冊上去的 handler 接住。 */
function capture(tool: { register: (server: McpServer) => void }): Handler {
  let handler: Handler | undefined;
  const server = {
    registerTool: (_name: string, _config: unknown, callback: Handler) => {
      handler = callback;
    },
  } as unknown as McpServer;
  tool.register(server);
  ok(handler !== undefined, 'the tool registered no handler');
  return handler;
}

/** 假的上下文。`scopes` 給 undefined 表示這台沒有驗證層。 */
function context(scopes?: readonly string[]): ServerContext {
  return {
    mcpReq: { method: 'tools/call' },
    ...(scopes === undefined ? {} : { http: { authInfo: { token: '', clientId: 'LG-TEST', scopes: [...scopes] } } }),
  } as unknown as ServerContext;
}

const guarded = defineTool({
  name: 'guarded',
  description: 'Needs a scope.',
  inputSchema: z.object({ value: z.string() }),
  requiredScope: Scope.NotesDelete,
  call: async ({ value }) => success({ value }),
});

const open = defineTool({
  name: 'open',
  description: 'Needs nothing.',
  inputSchema: z.object({ value: z.string() }),
  call: async ({ value }) => success({ value }),
});

describe('defineTool authorization', () => {
  it('runs the Tool when the caller holds the scope', async () => {
    const result = await capture(guarded)({ value: 'x' }, context([Scope.NotesDelete]));
    strictEqual(result['isError'], false);
  });

  it('refuses when the caller holds a different scope', async () => {
    const result = await capture(guarded)({ value: 'x' }, context([Scope.NotesRead]));
    strictEqual(result['isError'], true);
    strictEqual((result['structuredContent'] as Record<string, unknown>)['requiredScope'], Scope.NotesDelete);
  });

  it('says only a human can fix it, because the caller cannot widen its own token', () => {
    return capture(guarded)({ value: 'x' }, context([])).then((result) => {
      strictEqual((result['structuredContent'] as Record<string, unknown>)['remedy'], 'ASK_OPERATOR');
    });
  });

  it('runs the Tool when there is no authenticator at all', async () => {
    // stdio 走的就是這條：沒有身分就沒有授權可言。
    const result = await capture(guarded)({ value: 'x' }, context());
    strictEqual(result['isError'], false);
  });

  it('lets a Tool with no declared scope through for anyone', async () => {
    const result = await capture(open)({ value: 'x' }, context([]));
    strictEqual(result['isError'], false);
  });
});

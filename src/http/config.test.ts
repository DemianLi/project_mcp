/**
 * 設定與守門規則，不用開 socket 就測得出來。
 *
 * 守門那幾條是這個檔案最重要的部分：綁 loopback 什麼都可以省，綁其他位址少一樣就
 * 拒絕啟動。所以每一條「拒絕」都要有一個測試釘住。
 */
import { ok, strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { HTTP_DEFAULTS, isLoopback, readHttpConfig, type HttpConfig } from './config.js';

const SECRET = 'x'.repeat(32);

function reasonOf(env: NodeJS.ProcessEnv): string {
  const result = readHttpConfig(env);
  ok(!result.ok, `expected a refusal, got ${JSON.stringify(result)}`);
  return result.reason;
}

function configOf(env: NodeJS.ProcessEnv): HttpConfig {
  const result = readHttpConfig(env);
  ok(result.ok, `expected a config, got ${JSON.stringify(result)}`);
  return result.config;
}

describe('isLoopback', () => {
  it('knows the three that are', () => {
    for (const host of ['127.0.0.1', '::1', 'localhost']) {
      strictEqual(isLoopback(host), true, host);
    }
  });

  it('does not mistake a wildcard bind for loopback', () => {
    for (const host of ['0.0.0.0', '::', '10.0.0.5']) {
      strictEqual(isLoopback(host), false, host);
    }
  });
});

describe('readHttpConfig', () => {
  it('defaults to loopback, so an unconfigured start is never exposed', () => {
    const config = configOf({});
    strictEqual(config.host, HTTP_DEFAULTS.host);
    strictEqual(isLoopback(config.host), true);
    strictEqual(config.port, HTTP_DEFAULTS.port);
    strictEqual(config.path, HTTP_DEFAULTS.path);
  });

  it('takes host, port and path from the environment', () => {
    const config = configOf({ MCP_HTTP_HOST: 'localhost', MCP_HTTP_PORT: '9999', MCP_HTTP_PATH: '/rpc' });
    strictEqual(config.host, 'localhost');
    strictEqual(config.port, 9999);
    strictEqual(config.path, '/rpc');
  });

  it('names the variable it did not like', () => {
    ok(reasonOf({ MCP_HTTP_PORT: 'eighty' }).includes('MCP_HTTP_PORT'));
    ok(reasonOf({ MCP_HTTP_SHUTDOWN_GRACE_MS: '-1' }).includes('MCP_HTTP_SHUTDOWN_GRACE_MS'));
  });

  it('rejects a port outside the range', () => {
    ok(reasonOf({ MCP_HTTP_PORT: '70000' }).includes('65535'));
  });

  it('takes 0 to mean "pick one for me"', () => {
    strictEqual(configOf({ MCP_HTTP_PORT: '0' }).port, 0);
  });

  it('rejects a path that is not a path', () => {
    ok(reasonOf({ MCP_HTTP_PATH: 'mcp' }).includes('must start with /'));
  });

  it('refuses to serve off loopback with no authentication', () => {
    ok(reasonOf({ MCP_HTTP_HOST: '0.0.0.0' }).includes('no authentication'));
  });

  it('will not take a vague yes for that answer', () => {
    // 只有一個值算數，`true` / `1` 都不算——這一步要刻意到不可能手滑。
    for (const value of ['true', '1', 'yes', 'YES-I-KNOW']) {
      ok(reasonOf({ MCP_HTTP_HOST: '0.0.0.0', MCP_HTTP_ALLOW_UNAUTHENTICATED: value }).includes('no authentication'), value);
    }
  });

  it('still refuses off loopback without a shared requestState secret', () => {
    const reason = reasonOf({ MCP_HTTP_HOST: '0.0.0.0', MCP_HTTP_ALLOW_UNAUTHENTICATED: 'yes-i-know' });
    ok(reason.includes('REQUEST_STATE_SECRET'));
  });

  it('rejects a secret that is too short to be one', () => {
    const reason = reasonOf({
      MCP_HTTP_HOST: '0.0.0.0',
      MCP_HTTP_ALLOW_UNAUTHENTICATED: 'yes-i-know',
      REQUEST_STATE_SECRET: 'short',
    });
    ok(reason.includes('REQUEST_STATE_SECRET'));
  });

  it('serves off loopback once both are answered for', () => {
    const config = configOf({
      MCP_HTTP_HOST: '0.0.0.0',
      MCP_HTTP_ALLOW_UNAUTHENTICATED: 'yes-i-know',
      REQUEST_STATE_SECRET: SECRET,
    });
    strictEqual(config.host, '0.0.0.0');
    strictEqual(config.allowUnauthenticated, true);
  });

  it('asks for none of that on loopback', () => {
    const config = configOf({ MCP_HTTP_HOST: '127.0.0.1' });
    strictEqual(config.allowUnauthenticated, false);
  });
});

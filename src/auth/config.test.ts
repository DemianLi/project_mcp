/**
 * 驗證層的設定。純函式，不解金鑰也不連 JWKS。
 *
 * 這裡的每一條「拒絕」都是刻意的：設定寫錯的代價是「以為有驗證，其實沒有」，那比
 * 啟動失敗糟糕得多。
 */
import { ok, strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { readAuthConfig, type AuthConfig } from './config.js';

const JWT = {
  MCP_AUTH_MODE: 'jwt',
  MCP_AUTH_JWT_ISSUER: 'https://auth.example.gov.tw',
  MCP_AUTH_JWT_AUDIENCE: 'https://mcp.example.gov.tw',
  MCP_AUTH_JWT_JWKS_URL: 'https://auth.example.gov.tw/jwks',
};

function reasonOf(env: NodeJS.ProcessEnv): string {
  const result = readAuthConfig(env);
  ok(!result.ok, `expected a refusal, got ${JSON.stringify(result)}`);
  return result.reason;
}

function configOf(env: NodeJS.ProcessEnv): AuthConfig {
  const result = readAuthConfig(env);
  ok(result.ok, `expected a config, got ${JSON.stringify(result)}`);
  return result.config;
}

describe('readAuthConfig', () => {
  it('defaults to no authentication, which only loopback may serve', () => {
    strictEqual(configOf({}).mode, 'none');
  });

  it('rejects a mode it does not know, rather than falling back to none', () => {
    ok(reasonOf({ MCP_AUTH_MODE: 'basic' }).includes('MCP_AUTH_MODE'));
  });

  it('needs an issuer', () => {
    ok(reasonOf({ ...JWT, MCP_AUTH_JWT_ISSUER: '' }).includes('MCP_AUTH_JWT_ISSUER'));
  });

  it('needs an audience, and says why', () => {
    const reason = reasonOf({ ...JWT, MCP_AUTH_JWT_AUDIENCE: '' });
    ok(reason.includes('MCP_AUTH_JWT_AUDIENCE'));
    ok(reason.includes('another service'));
  });

  it('needs somewhere to get the public key', () => {
    const { MCP_AUTH_JWT_JWKS_URL: _dropped, ...withoutKey } = JWT;
    ok(reasonOf(withoutKey).includes('MCP_AUTH_JWT_JWKS_URL'));
  });

  it('refuses two key sources at once', () => {
    ok(reasonOf({ ...JWT, MCP_AUTH_JWT_PUBLIC_KEY: '-----BEGIN PUBLIC KEY-----' }).includes('not both'));
  });

  it('refuses a JWKS URL that is not https', () => {
    ok(reasonOf({ ...JWT, MCP_AUTH_JWT_JWKS_URL: 'http://auth.example.gov.tw/jwks' }).includes('https'));
  });

  it('allows a loopback JWKS URL, for development', () => {
    const config = configOf({ ...JWT, MCP_AUTH_JWT_JWKS_URL: 'http://127.0.0.1:9000/jwks' });
    strictEqual(config.mode === 'jwt' && config.key.kind, 'jwks');
  });

  it('takes a PEM public key instead', () => {
    const { MCP_AUTH_JWT_JWKS_URL: _dropped, ...withPem } = JWT;
    const config = configOf({ ...withPem, MCP_AUTH_JWT_PUBLIC_KEY: '-----BEGIN PUBLIC KEY-----\nx\n-----END PUBLIC KEY-----' });
    strictEqual(config.mode === 'jwt' && config.key.kind, 'pem');
  });

  it('defaults to asymmetric algorithms only', () => {
    const config = configOf(JWT);
    ok(config.mode === 'jwt');
    strictEqual(config.algorithms.includes('ES256'), true);
    strictEqual(config.algorithms.some((name) => name.startsWith('HS')), false);
  });

  it('refuses a shared-secret algorithm, and says why', () => {
    // HS256 需要 Server 持有簽得出 token 的密鑰。Server 是對外那一台，被攻破就等於
    // 簽發權被拿走——所以這不是可以設定的取捨，是直接拒絕。
    const reason = reasonOf({ ...JWT, MCP_AUTH_JWT_ALGORITHMS: 'HS256' });
    ok(reason.includes('HS256'));
    ok(reason.includes('public key'));
  });

  it('refuses "none" as an algorithm', () => {
    ok(reasonOf({ ...JWT, MCP_AUTH_JWT_ALGORITHMS: 'none' }).includes('none'));
  });
});

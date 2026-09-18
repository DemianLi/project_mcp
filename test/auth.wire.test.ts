/**
 * Acceptance 層：驗證層。
 *
 * 用真的 ES256 簽章，不用假的驗證器——要測的正是「什麼樣的 token 會被擋下來」，
 * 換成 mock 就等於自己出題自己改。
 */
import { match, ok, strictEqual } from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { HttpServer } from './support/httpClient.js';
import { AUDIENCE, ISSUER, createIssuer, goodClaims, type Issuer } from './support/tokens.js';

describe('with a JWT authenticator', () => {
  let issuer: Issuer;
  let server: HttpServer;

  before(async () => {
    issuer = await createIssuer();
    server = await HttpServer.start({
      DATABASE_URL: '',
      MCP_AUTH_MODE: 'jwt',
      MCP_AUTH_JWT_ISSUER: ISSUER,
      MCP_AUTH_JWT_AUDIENCE: AUDIENCE,
      MCP_AUTH_JWT_PUBLIC_KEY: issuer.publicKeyPem,
    });
  });

  after(async () => {
    await server.stop();
  });

  async function bearer(claims: Record<string, unknown>, expiresIn = '5m'): Promise<string> {
    return `Bearer ${await issuer.sign(claims, { expiresIn })}`;
  }

  it('lets a properly signed token through', async () => {
    const { status, body } = await server.post(1, 'tools/list', {}, await bearer(goodClaims()));
    strictEqual(status, 200);
    ok(body.result !== undefined, JSON.stringify(body));
  });

  it('refuses a request with no token', async () => {
    const { status } = await server.post(2, 'tools/list');
    strictEqual(status, 401);
  });

  it('refuses a token from another issuer', async () => {
    const { status } = await server.post(3, 'tools/list', {}, await bearer({ ...goodClaims(), iss: 'https://evil.example' }));
    strictEqual(status, 401);
  });

  it('refuses a token minted for another service', async () => {
    // 這就是 audience 綁定的用處：地方機關手上給別的系統用的 token，不能拿來打這台。
    const { status } = await server.post(4, 'tools/list', {}, await bearer({ ...goodClaims(), aud: 'https://other.example' }));
    strictEqual(status, 401);
  });

  it('refuses an expired token', async () => {
    const { status } = await server.post(5, 'tools/list', {}, await bearer(goodClaims(), '-1s'));
    strictEqual(status, 401);
  });

  it('refuses a token with no expiry at all', async () => {
    const token = `Bearer ${await issuer.sign(goodClaims())}`;
    strictEqual((await server.post(6, 'tools/list', {}, token)).status, 401);
  });

  it('refuses a tampered token', async () => {
    const token = await issuer.sign(goodClaims(), { expiresIn: '5m' });
    const tampered = `${token.slice(0, -2)}${token.endsWith('AA') ? 'BB' : 'AA'}`;
    strictEqual((await server.post(7, 'tools/list', {}, `Bearer ${tampered}`)).status, 401);
  });

  it('refuses a token that names no subject', async () => {
    const { sub: _dropped, ...noSubject } = goodClaims();
    strictEqual((await server.post(8, 'tools/list', {}, await bearer(noSubject)).then((r) => r)).status, 401);
  });

  it('refuses an Authorization header that is not Bearer', async () => {
    strictEqual((await server.post(9, 'tools/list', {}, 'Basic dXNlcjpwYXNz')).status, 401);
  });

  it('says how to authenticate, without saying what went wrong', async () => {
    const response = await fetch(`${server.origin}/mcp`, { method: 'POST', headers: { 'Mcp-Method': 'tools/list' } });
    strictEqual(response.status, 401);
    match(response.headers.get('www-authenticate') ?? '', /^Bearer error="/);
    // 過期、簽章不對、受眾不符對攻擊者來說是三種不同的提示，所以回應裡不該分得出來。
    const body = await response.text();
    strictEqual(/expired|signature|audience|issuer/i.test(body), false, body);
  });

  it('leaves the probes open, because kubelet has no token', async () => {
    strictEqual((await server.get('/healthz')).status, 200);
    strictEqual((await server.get('/readyz')).status, 200);
  });

  it('records which agency called which Tool', async () => {
    await server.post(10, 'tools/call', { name: 'get_weather', arguments: { city: 'Taipei' } }, await bearer(goodClaims('LG-042')));
    // log 是非同步寫出去的，等它落地。
    await new Promise((resolve) => setTimeout(resolve, 200));
    const entry = server.logLines.find((line) => line['event'] === 'audit' && line['tool'] === 'get_weather');
    ok(entry !== undefined, JSON.stringify(server.logLines));
    strictEqual(entry['agency'], 'LG-042');
    strictEqual(entry['outcome'], 'ok');
    ok(typeof entry['ms'] === 'number');
  });

  it('keeps the call arguments out of the audit trail', async () => {
    await server.post(11, 'tools/call', { name: 'get_weather', arguments: { city: 'Tokyo' } }, await bearer(goodClaims()));
    await new Promise((resolve) => setTimeout(resolve, 200));
    // 稽核記的是 Shape 不是 Content：要看內容應該去查資料本身，不是翻 log。
    const audits = server.logLines.filter((line) => line['event'] === 'audit');
    strictEqual(audits.some((line) => JSON.stringify(line).includes('Tokyo')), false);
  });
});

describe('with no authenticator', () => {
  it('serves off loopback and calls the caller anonymous', async () => {
    const server = await HttpServer.start({ DATABASE_URL: '' });
    try {
      const { status } = await server.post(1, 'tools/call', { name: 'get_weather', arguments: { city: 'Taipei' } });
      strictEqual(status, 200);
      await new Promise((resolve) => setTimeout(resolve, 200));
      const entry = server.logLines.find((line) => line['event'] === 'audit');
      strictEqual(entry?.['agency'], 'anonymous');
    } finally {
      await server.stop();
    }
  });

  it('will not serve off loopback without the explicit admission', async () => {
    await refused({ MCP_HTTP_HOST: '0.0.0.0' }, /no authentication/);
  });
});

describe('configuring authentication', () => {
  it('lets the server serve off loopback without the admission flag', async () => {
    const issuer = await createIssuer();
    const server = await HttpServer.start({
      DATABASE_URL: '',
      MCP_HTTP_HOST: '127.0.0.1',
      MCP_AUTH_MODE: 'jwt',
      MCP_AUTH_JWT_ISSUER: ISSUER,
      MCP_AUTH_JWT_AUDIENCE: AUDIENCE,
      MCP_AUTH_JWT_PUBLIC_KEY: issuer.publicKeyPem,
      // 對外服務仍然要這一把，因為多回合狀態要跨副本。
      REQUEST_STATE_SECRET: 'y'.repeat(32),
    });
    try {
      const start = server.logLines.find((line) => line['event'] === 'start');
      match(String(start?.['auth']), /^jwt issuer=/);
    } finally {
      await server.stop();
    }
  });

  it('refuses a misconfigured authenticator rather than serving without one', async () => {
    await refused({ MCP_AUTH_MODE: 'jwt', MCP_AUTH_JWT_ISSUER: ISSUER }, /MCP_AUTH_JWT_AUDIENCE/);
  });
});

async function refused(env: NodeJS.ProcessEnv, expected: RegExp): Promise<void> {
  try {
    const server = await HttpServer.start(env);
    await server.stop();
    ok(false, 'expected the server to refuse to start');
  } catch (cause) {
    match(cause instanceof Error ? cause.message : String(cause), expected);
  }
}

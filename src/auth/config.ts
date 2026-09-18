/**
 * 驗證層的設定，全部從環境變數讀。純函式，不連線也不解金鑰。
 *
 * 只有兩種模式。`none` 是開發用的，沒有身分；`jwt` 驗中央機關簽發的 token。
 * 之後若改用機關憑證，是在這裡多一種模式，不是改現有的。
 */

export type AuthConfig =
  | { readonly mode: 'none' }
  | {
      readonly mode: 'jwt';
      /** 預期的 `iss`。簽發者換掉時這裡要跟著換。 */
      readonly issuer: string;
      /** 預期的 `aud`：這台 Server 的資源識別碼。發給別的服務的 token 因此打不進來。 */
      readonly audience: string;
      /** 只認這些演算法。非對稱簽章——Server 只該有公鑰，不該有簽得出 token 的東西。 */
      readonly algorithms: readonly string[];
      /** 公鑰從哪裡來。JWKS 端點可以輪替金鑰；PEM 適合還沒有簽發服務的階段。 */
      readonly key: { readonly kind: 'jwks'; readonly url: string } | { readonly kind: 'pem'; readonly pem: string };
    };

export type AuthConfigResult =
  | { readonly ok: true; readonly config: AuthConfig }
  | { readonly ok: false; readonly reason: string };

/**
 * 預設只認這兩個。
 *
 * 不含任何 HMAC（`HS*`）：那需要 Server 持有簽得出 token 的密鑰，而 Server 是對外的
 * 那一台，被攻破就等於簽發權被拿走。也不含 `none`——那不是演算法，是漏洞。
 */
const DEFAULT_ALGORITHMS = ['ES256', 'RS256'] as const;
const ALLOWED_ALGORITHMS = new Set(['ES256', 'ES384', 'ES512', 'RS256', 'RS384', 'RS512', 'PS256', 'PS384', 'PS512']);

export function readAuthConfig(env: NodeJS.ProcessEnv = process.env): AuthConfigResult {
  const mode = env['MCP_AUTH_MODE'] ?? 'none';

  if (mode === 'none') {
    return { ok: true, config: { mode: 'none' } };
  }
  if (mode !== 'jwt') {
    return { ok: false, reason: `MCP_AUTH_MODE must be "none" or "jwt", got ${JSON.stringify(mode)}` };
  }

  const issuer = env['MCP_AUTH_JWT_ISSUER'];
  if (issuer === undefined || issuer.trim() === '') {
    return { ok: false, reason: 'MCP_AUTH_JWT_ISSUER is required when MCP_AUTH_MODE=jwt' };
  }

  const audience = env['MCP_AUTH_JWT_AUDIENCE'];
  if (audience === undefined || audience.trim() === '') {
    return {
      ok: false,
      reason:
        'MCP_AUTH_JWT_AUDIENCE is required when MCP_AUTH_MODE=jwt. ' +
        'It is this server\'s resource identifier; without it a token minted for another service is accepted here.',
    };
  }

  const algorithms = (env['MCP_AUTH_JWT_ALGORITHMS'] ?? DEFAULT_ALGORITHMS.join(','))
    .split(',')
    .map((name) => name.trim())
    .filter((name) => name !== '');
  if (algorithms.length === 0) {
    return { ok: false, reason: 'MCP_AUTH_JWT_ALGORITHMS must name at least one algorithm' };
  }
  for (const name of algorithms) {
    if (!ALLOWED_ALGORITHMS.has(name)) {
      return {
        ok: false,
        reason:
          `MCP_AUTH_JWT_ALGORITHMS: ${name} is not an asymmetric signature algorithm. ` +
          'This server verifies with a public key and must never hold one that can mint tokens.',
      };
    }
  }

  const jwks = env['MCP_AUTH_JWT_JWKS_URL'];
  const pem = env['MCP_AUTH_JWT_PUBLIC_KEY'];
  if (jwks !== undefined && jwks.trim() !== '' && pem !== undefined && pem.trim() !== '') {
    return { ok: false, reason: 'Set either MCP_AUTH_JWT_JWKS_URL or MCP_AUTH_JWT_PUBLIC_KEY, not both' };
  }
  if (jwks !== undefined && jwks.trim() !== '') {
    let url: URL;
    try {
      url = new URL(jwks);
    } catch {
      return { ok: false, reason: `MCP_AUTH_JWT_JWKS_URL is not a URL: ${JSON.stringify(jwks)}` };
    }
    if (url.protocol !== 'https:' && url.hostname !== 'localhost' && url.hostname !== '127.0.0.1') {
      return { ok: false, reason: 'MCP_AUTH_JWT_JWKS_URL must be https (or loopback for development)' };
    }
    return { ok: true, config: { mode: 'jwt', issuer, audience, algorithms, key: { kind: 'jwks', url: jwks } } };
  }
  if (pem !== undefined && pem.trim() !== '') {
    return { ok: true, config: { mode: 'jwt', issuer, audience, algorithms, key: { kind: 'pem', pem } } };
  }

  return {
    ok: false,
    reason: 'MCP_AUTH_MODE=jwt needs either MCP_AUTH_JWT_JWKS_URL or MCP_AUTH_JWT_PUBLIC_KEY',
  };
}

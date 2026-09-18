/**
 * 接縫：把一個請求的 headers 換成一個機關身分，換不出來就丟 `AuthError`。
 *
 * 只有這個檔案知道身分是怎麼證明的。Tool、稽核紀錄、多回合狀態都只看 `Principal`，
 * 所以之後改用機關憑證（mTLS，由 ingress 終結、把 subject DN 傳進來）時，多的是這裡的
 * 一個實作，其他地方不動。
 */
import { createRemoteJWKSet, importSPKI, jwtVerify, type JWTPayload, type JWTVerifyGetKey } from 'jose';
import type { AuthConfig } from './config.js';
import { AuthError, ANONYMOUS, type Principal } from './principal.js';

export interface Authenticator {
  /** 啟動時記一行，讓看 log 的人知道這台在驗什麼。 */
  readonly describe: string;
  authenticate(headers: Headers): Promise<Principal>;
}

export function createAuthenticator(config: AuthConfig): Authenticator {
  if (config.mode === 'none') {
    return {
      describe: 'none',
      authenticate: async () => ANONYMOUS,
    };
  }

  const getKey = keyResolver(config);

  return {
    describe: `jwt issuer=${config.issuer} audience=${config.audience} algorithms=${config.algorithms.join(',')}`,
    authenticate: async (headers) => {
      const token = bearer(headers.get('authorization'));
      let payload: JWTPayload;
      try {
        ({ payload } = await jwtVerify(token, getKey, {
          issuer: config.issuer,
          audience: config.audience,
          // 明列演算法。不寫的話，攻擊者換一個 `alg` 就可能繞過驗證。
          algorithms: [...config.algorithms],
        }));
      } catch {
        // 不把原因回給呼叫端：過期、簽章不對、受眾不符，對攻擊者來說是三種不同的提示。
        throw new AuthError(401, 'invalid_token', 'The access token is not valid for this server.');
      }

      const agency = firstString(payload['sub'], payload['client_id']);
      if (agency === undefined) {
        throw new AuthError(401, 'invalid_token', 'The access token names no subject.');
      }
      if (typeof payload.exp !== 'number') {
        // SDK 的 bearer 驗證也擋這一條：沒有到期時間的 token 等於永久有效。
        throw new AuthError(401, 'invalid_token', 'The access token has no expiry.');
      }

      return { agency, scopes: scopesOf(payload), expiresAt: payload.exp };
    },
  };
}

function keyResolver(config: Extract<AuthConfig, { mode: 'jwt' }>): JWTVerifyGetKey {
  if (config.key.kind === 'jwks') {
    // 遠端 JWKS 會自己快取並在遇到沒見過的 kid 時重取，所以簽發端輪替金鑰不必重啟這台。
    return createRemoteJWKSet(new URL(config.key.url));
  }
  // PEM：還沒有簽發服務的階段用。一把公鑰配一種演算法，所以取設定裡的第一個。
  // 之後換成 JWKS 只是換一個環境變數，這個檔案不動。
  const imported = importSPKI(config.key.pem, config.algorithms[0] ?? 'ES256');
  return async () => imported;
}

function bearer(header: string | null): string {
  if (header === null || header.trim() === '') {
    throw new AuthError(401, 'invalid_request', 'An access token is required.');
  }
  const [scheme, ...rest] = header.trim().split(/\s+/);
  if (scheme?.toLowerCase() !== 'bearer' || rest.length !== 1 || rest[0] === undefined) {
    throw new AuthError(401, 'invalid_request', 'Authorization must be a Bearer token.');
  }
  return rest[0];
}

function firstString(...values: unknown[]): string | undefined {
  for (const value of values) {
    if (typeof value === 'string' && value.trim() !== '') {
      return value;
    }
  }
  return undefined;
}

/** `scope` 是空白分隔的字串（OAuth 的慣例），有些簽發端給陣列。兩種都收。 */
function scopesOf(payload: JWTPayload): readonly string[] {
  const scope = payload['scope'];
  if (typeof scope === 'string') {
    return scope.split(/\s+/).filter((one) => one !== '');
  }
  if (Array.isArray(scope)) {
    return scope.filter((one): one is string => typeof one === 'string');
  }
  return [];
}

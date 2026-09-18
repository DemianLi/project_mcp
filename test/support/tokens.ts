/**
 * 測試用的簽發端：一把臨時的 ES256 金鑰，簽得出各種對的與不對的 token。
 *
 * 用真的簽章而不是假的驗證器，是因為要測的正是「什麼樣的 token 會被擋下來」——
 * 換成 mock 就等於自己出題自己改。
 */
import { SignJWT, exportSPKI, generateKeyPair, type CryptoKey } from 'jose';

export const ISSUER = 'https://auth.test.local';
export const AUDIENCE = 'https://mcp.test.local';

export interface Issuer {
  /** 公鑰的 PEM，餵給 MCP_AUTH_JWT_PUBLIC_KEY。 */
  readonly publicKeyPem: string;
  sign(claims: Record<string, unknown>, options?: { expiresIn?: string; algorithm?: string }): Promise<string>;
}

export async function createIssuer(): Promise<Issuer> {
  const { privateKey, publicKey } = await generateKeyPair('ES256', { extractable: true });
  const publicKeyPem = await exportSPKI(publicKey as CryptoKey);

  return {
    publicKeyPem,
    sign: async (claims, options = {}) => {
      let jwt = new SignJWT(claims)
        .setProtectedHeader({ alg: options.algorithm ?? 'ES256' })
        .setIssuedAt();
      if (options.expiresIn !== undefined) {
        jwt = jwt.setExpirationTime(options.expiresIn);
      }
      return jwt.sign(privateKey);
    },
  };
}

/** 一個正常的、會被接受的 token。 */
export function goodClaims(agency = 'LG-001', scopes = 'notes:read notes:write'): Record<string, unknown> {
  return { iss: ISSUER, aud: AUDIENCE, sub: agency, scope: scopes };
}

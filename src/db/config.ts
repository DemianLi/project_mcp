/**
 * 連線池的設定，全部從環境變數讀。
 *
 * 純函式，不連線也不建池——因此「設定對不對」測得起來，不需要一個資料庫。
 */

export interface DatabaseConfig {
  readonly connectionString: string;
  /** 池子最多同時開幾條連線。 */
  readonly max: number;
  /** 閒置多久之後把連線還回去。 */
  readonly idleTimeoutMillis: number;
  /** 等一條可用連線最多等多久。 */
  readonly connectionTimeoutMillis: number;
  /** 單一 SQL 最多跑多久，由 PostgreSQL 自己中止。 */
  readonly statementTimeoutMillis: number;
}

export type ConfigResult =
  | { readonly ok: true; readonly config: DatabaseConfig }
  | { readonly ok: false; readonly reason: string };

export const DEFAULTS = {
  max: 10,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 5_000,
  statementTimeoutMillis: 10_000,
} as const;

/**
 * 讀出設定。
 *
 * 沒有 `DATABASE_URL` 就是「這個部署不用資料庫」，不是錯誤——不碰資料庫的 Server
 * 不該因為少一個環境變數而起不來。數字設錯才是錯誤，而且在用到之前就說清楚哪裡錯。
 */
export function readDatabaseConfig(env: NodeJS.ProcessEnv = process.env): ConfigResult {
  const connectionString = env['DATABASE_URL'];
  if (connectionString === undefined || connectionString.trim() === '') {
    return { ok: false, reason: 'DATABASE_URL is not set' };
  }

  const max = readPositiveInt(env, 'DATABASE_POOL_MAX', DEFAULTS.max);
  if (typeof max === 'string') {
    return { ok: false, reason: max };
  }

  const idleTimeoutMillis = readPositiveInt(env, 'DATABASE_IDLE_TIMEOUT_MS', DEFAULTS.idleTimeoutMillis);
  if (typeof idleTimeoutMillis === 'string') {
    return { ok: false, reason: idleTimeoutMillis };
  }

  const connectionTimeoutMillis = readPositiveInt(
    env,
    'DATABASE_CONNECTION_TIMEOUT_MS',
    DEFAULTS.connectionTimeoutMillis,
  );
  if (typeof connectionTimeoutMillis === 'string') {
    return { ok: false, reason: connectionTimeoutMillis };
  }

  const statementTimeoutMillis = readPositiveInt(
    env,
    'DATABASE_STATEMENT_TIMEOUT_MS',
    DEFAULTS.statementTimeoutMillis,
  );
  if (typeof statementTimeoutMillis === 'string') {
    return { ok: false, reason: statementTimeoutMillis };
  }

  return {
    ok: true,
    config: { connectionString, max, idleTimeoutMillis, connectionTimeoutMillis, statementTimeoutMillis },
  };
}

/** 回傳數字，或一句說明哪裡不對的話。 */
function readPositiveInt(env: NodeJS.ProcessEnv, name: string, fallback: number): number | string {
  const raw = env[name];
  if (raw === undefined || raw.trim() === '') {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    return `${name} must be a positive integer, got: ${raw}`;
  }
  return value;
}

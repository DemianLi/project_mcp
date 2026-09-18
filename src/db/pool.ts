/**
 * PostgreSQL 連線池。
 *
 * 懶建：沒有人查詢就沒有池子，也就沒有連線。這讓不用資料庫的部署與離線的測試
 * 都不必準備一個 PostgreSQL，而設定錯誤仍然會在第一次查詢時明確地講出來。
 *
 * 換成別的資料庫時，被換掉的是這個檔案與 config.ts，業務程式不動。
 */
import { Pool, type PoolClient, type QueryResultRow } from 'pg';
import { readDatabaseConfig } from './config.js';
import { log } from '../log.js';

/** 設定不全或設錯時丟出。呼叫端把它翻成 `ASK_OPERATOR`：只有人能修好。 */
export class DatabaseNotConfiguredError extends Error {
  constructor(reason: string) {
    super(`Database is not configured: ${reason}`);
    this.name = 'DatabaseNotConfiguredError';
  }
}

let pool: Pool | null = null;

/** 池子開了沒。給關機流程與測試用。 */
export function isPoolOpen(): boolean {
  return pool !== null;
}

/**
 * 取得池子，第一次呼叫才真的建立。
 *
 * @throws {DatabaseNotConfiguredError} 環境變數缺漏或設錯時。
 */
export function getPool(): Pool {
  if (pool !== null) {
    return pool;
  }

  const result = readDatabaseConfig();
  if (!result.ok) {
    throw new DatabaseNotConfiguredError(result.reason);
  }

  const { connectionString, max, idleTimeoutMillis, connectionTimeoutMillis, statementTimeoutMillis } =
    result.config;
  pool = new Pool({
    connectionString,
    max,
    idleTimeoutMillis,
    connectionTimeoutMillis,
    statement_timeout: statementTimeoutMillis,
  });

  // 閒置連線被伺服器或中間設備切斷時，pg 會在池子上發 error。不接這個事件，
  // 一個背景連線掛掉就會變成 unhandled error 而弄死整個行程。
  pool.on('error', (cause) => {
    log({ event: 'db.idleClientError', cause: String(cause) });
  });

  log({ event: 'db.poolOpened', max, idleTimeoutMillis, connectionTimeoutMillis, statementTimeoutMillis });
  return pool;
}

/** 一次查詢。大多數增刪改查只需要這個。 */
export async function query<T extends QueryResultRow = QueryResultRow>(
  text: string,
  values: readonly unknown[] = [],
): Promise<T[]> {
  const result = await getPool().query<T>(text, [...values]);
  return result.rows;
}

/**
 * 在一個交易裡跑一段程式；丟出例外就 rollback。
 *
 * 多步驟的寫入用這個，不要自己 acquire 連線——忘記 release 是連線池最常見的死法。
 */
export async function withTransaction<T>(work: (client: PoolClient) => Promise<T>): Promise<T> {
  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    const value = await work(client);
    await client.query('COMMIT');
    return value;
  } catch (cause) {
    await client.query('ROLLBACK').catch(() => {});
    throw cause;
  } finally {
    client.release();
  }
}

/** 關機時把池子收掉。沒開過就什麼都不做。 */
export async function closePool(): Promise<void> {
  if (pool === null) {
    return;
  }
  const closing = pool;
  pool = null;
  await closing.end();
  log({ event: 'db.poolClosed' });
}

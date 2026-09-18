/**
 * 把資料庫丟出來的例外翻成一個 Remedy。
 *
 * 分類的依據是「呼叫者下一步該做什麼」，不是成因。PostgreSQL 的 SQLSTATE 剛好把成因
 * 分得很細，這裡把它折成五種下一步——加新的 Tool 時不必重寫這段判斷。
 */
import { DatabaseNotConfiguredError } from '../db/pool.js';
import { Remedy } from './remedy.js';
import { failed, type CallToolBody } from './result.js';

/** 等一下再試就可能成功：逾時、序列化衝突、死結。 */
const RETRYABLE = new Set([
  '57014', // query_canceled，通常是 statement_timeout
  '40001', // serialization_failure
  '40P01', // deadlock_detected
  '55P03', // lock_not_available
]);

/** 改參數才可能成功：違反了資料表自己的約束。 */
const FIXABLE = new Set([
  '23502', // not_null_violation
  '23503', // foreign_key_violation
  '23505', // unique_violation
  '23514', // check_violation
  '22001', // string_data_right_truncation
]);

/** 呼叫者改不了：資料表不在、沒權限、連不上。要人去修環境。 */
const OPERATOR = new Set([
  '42P01', // undefined_table
  '42703', // undefined_column
  '42501', // insufficient_privilege
  '28P01', // invalid_password
  '3D000', // invalid_catalog_name
  '53300', // too_many_connections
]);

export function databaseFailure(cause: unknown): CallToolBody {
  if (cause instanceof DatabaseNotConfiguredError) {
    return failed(Remedy.AskOperator, cause.message);
  }

  const code = sqlState(cause);
  const message = cause instanceof Error ? cause.message : String(cause);

  if (code === undefined) {
    // 連不上根本拿不到 SQLSTATE：pg 丟的是 ECONNREFUSED 這類 Node 的錯。
    return failed(isConnectionError(cause) ? Remedy.AskOperator : Remedy.Unknown, message);
  }
  if (RETRYABLE.has(code)) {
    return failed(Remedy.Retry, message, { sqlState: code });
  }
  if (FIXABLE.has(code)) {
    return failed(Remedy.FixRequest, message, { sqlState: code });
  }
  if (OPERATOR.has(code) || code.startsWith('08')) {
    // 08 開頭是 connection_exception 那一整族。
    return failed(Remedy.AskOperator, message, { sqlState: code });
  }
  return failed(Remedy.Unknown, message, { sqlState: code });
}

/** pg 把 SQLSTATE 放在 error.code，但那個型別是 string，不保證存在。 */
function sqlState(cause: unknown): string | undefined {
  if (typeof cause !== 'object' || cause === null) {
    return undefined;
  }
  const code = (cause as { code?: unknown }).code;
  // Node 的系統錯誤也叫 code（ECONNREFUSED），用長度與字元區分：SQLSTATE 一律五碼。
  return typeof code === 'string' && /^[0-9A-Z]{5}$/.test(code) ? code : undefined;
}

function isConnectionError(cause: unknown): boolean {
  if (typeof cause !== 'object' || cause === null) {
    return false;
  }
  const code = (cause as { code?: unknown }).code;
  return (
    typeof code === 'string' &&
    ['ECONNREFUSED', 'ENOTFOUND', 'EHOSTUNREACH', 'ETIMEDOUT', 'ECONNRESET'].includes(code)
  );
}

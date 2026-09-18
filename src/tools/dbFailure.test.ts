/**
 * 錯誤怎麼分類，不需要資料庫也測得出來——`databaseFailure` 是純函式。
 */
import { strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { DatabaseNotConfiguredError } from '../db/pool.js';
import { databaseFailure } from './dbFailure.js';
import { Remedy } from './remedy.js';

/** pg 丟出來的錯長這樣：一般的 Error 加一個 SQLSTATE。 */
function sqlError(code: string): Error {
  return Object.assign(new Error(`boom ${code}`), { code });
}

function remedyOf(cause: unknown): string {
  return databaseFailure(cause).structuredContent['remedy'] as string;
}

describe('databaseFailure', () => {
  it('says only a human can fix a missing configuration', () => {
    strictEqual(remedyOf(new DatabaseNotConfiguredError('DATABASE_URL is not set')), Remedy.AskOperator);
  });

  it('says retry for a statement timeout', () => {
    strictEqual(remedyOf(sqlError('57014')), Remedy.Retry);
  });

  it('says retry for a deadlock', () => {
    strictEqual(remedyOf(sqlError('40P01')), Remedy.Retry);
  });

  it('says fix the request when the row broke a constraint', () => {
    strictEqual(remedyOf(sqlError('23505')), Remedy.FixRequest);
    strictEqual(remedyOf(sqlError('23514')), Remedy.FixRequest);
  });

  it('says ask an operator when the table is missing', () => {
    strictEqual(remedyOf(sqlError('42P01')), Remedy.AskOperator);
  });

  it('treats the whole 08 class as an environment problem', () => {
    strictEqual(remedyOf(sqlError('08006')), Remedy.AskOperator);
  });

  it('says ask an operator when the database refused the connection', () => {
    strictEqual(remedyOf(Object.assign(new Error('connect ECONNREFUSED'), { code: 'ECONNREFUSED' })), Remedy.AskOperator);
  });

  it('does not mistake a Node error code for a SQLSTATE', () => {
    const body = databaseFailure(Object.assign(new Error('nope'), { code: 'ECONNREFUSED' }));
    strictEqual(body.structuredContent['sqlState'], undefined);
  });

  it('admits when it does not recognise the failure', () => {
    strictEqual(remedyOf(sqlError('XX000')), Remedy.Unknown);
    strictEqual(remedyOf('a string, somehow'), Remedy.Unknown);
  });

  it('always reports a failure, never a success', () => {
    strictEqual(databaseFailure(sqlError('23505')).isError, true);
  });
});

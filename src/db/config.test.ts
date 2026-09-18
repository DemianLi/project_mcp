import { ok, strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { DEFAULTS, readDatabaseConfig } from './config.js';

const URL = 'postgres://user:pw@localhost:5432/db';

describe('readDatabaseConfig', () => {
  it('fills in every default when only the URL is set', () => {
    const result = readDatabaseConfig({ DATABASE_URL: URL });
    ok(result.ok);
    strictEqual(result.config.connectionString, URL);
    strictEqual(result.config.max, DEFAULTS.max);
    strictEqual(result.config.idleTimeoutMillis, DEFAULTS.idleTimeoutMillis);
    strictEqual(result.config.connectionTimeoutMillis, DEFAULTS.connectionTimeoutMillis);
    strictEqual(result.config.statementTimeoutMillis, DEFAULTS.statementTimeoutMillis);
  });

  it('treats a missing URL as "no database here", with a reason', () => {
    const result = readDatabaseConfig({});
    strictEqual(result.ok, false);
    if (result.ok) return;
    strictEqual(result.reason, 'DATABASE_URL is not set');
  });

  it('treats a blank URL the same as a missing one', () => {
    strictEqual(readDatabaseConfig({ DATABASE_URL: '   ' }).ok, false);
  });

  it('reads the overrides', () => {
    const result = readDatabaseConfig({
      DATABASE_URL: URL,
      DATABASE_POOL_MAX: '25',
      DATABASE_IDLE_TIMEOUT_MS: '1000',
      DATABASE_CONNECTION_TIMEOUT_MS: '2000',
      DATABASE_STATEMENT_TIMEOUT_MS: '3000',
    });
    ok(result.ok);
    strictEqual(result.config.max, 25);
    strictEqual(result.config.idleTimeoutMillis, 1000);
    strictEqual(result.config.connectionTimeoutMillis, 2000);
    strictEqual(result.config.statementTimeoutMillis, 3000);
  });

  it('names the variable that is wrong, rather than silently using a default', () => {
    const result = readDatabaseConfig({ DATABASE_URL: URL, DATABASE_POOL_MAX: 'ten' });
    strictEqual(result.ok, false);
    if (result.ok) return;
    ok(result.reason.includes('DATABASE_POOL_MAX'));
  });

  it('refuses zero and negative pool sizes', () => {
    strictEqual(readDatabaseConfig({ DATABASE_URL: URL, DATABASE_POOL_MAX: '0' }).ok, false);
    strictEqual(readDatabaseConfig({ DATABASE_URL: URL, DATABASE_POOL_MAX: '-1' }).ok, false);
  });

  it('refuses a fractional timeout', () => {
    strictEqual(readDatabaseConfig({ DATABASE_URL: URL, DATABASE_IDLE_TIMEOUT_MS: '1.5' }).ok, false);
  });

  it('lets a blank override fall back to the default', () => {
    const result = readDatabaseConfig({ DATABASE_URL: URL, DATABASE_POOL_MAX: '' });
    ok(result.ok);
    strictEqual(result.config.max, DEFAULTS.max);
  });
});

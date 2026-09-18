import { ok, rejects, strictEqual, throws } from 'node:assert/strict';
import { afterEach, describe, it } from 'node:test';
import { DatabaseNotConfiguredError, closePool, getPool, isPoolOpen, query } from './pool.js';

const SAVED = process.env['DATABASE_URL'];

afterEach(async () => {
  await closePool();
  if (SAVED === undefined) {
    delete process.env['DATABASE_URL'];
  } else {
    process.env['DATABASE_URL'] = SAVED;
  }
});

describe('the pool', () => {
  it('is not open until something asks for it', () => {
    strictEqual(isPoolOpen(), false);
  });

  it('closes cleanly when it was never opened', async () => {
    await closePool();
    strictEqual(isPoolOpen(), false);
  });

  it('refuses to open without a connection string, and says so', () => {
    delete process.env['DATABASE_URL'];
    throws(() => getPool(), DatabaseNotConfiguredError);
    strictEqual(isPoolOpen(), false);
  });

  it('reports a misconfigured number rather than opening a half-configured pool', () => {
    process.env['DATABASE_URL'] = 'postgres://user:pw@localhost:5432/db';
    process.env['DATABASE_POOL_MAX'] = 'ten';
    try {
      throws(() => getPool(), DatabaseNotConfiguredError);
      strictEqual(isPoolOpen(), false);
    } finally {
      delete process.env['DATABASE_POOL_MAX'];
    }
  });

  it('opens once and hands back the same pool, without connecting', () => {
    process.env['DATABASE_URL'] = 'postgres://user:pw@localhost:5432/db';
    const first = getPool();
    ok(isPoolOpen());
    strictEqual(getPool(), first);
  });

  it('opens again after being closed', async () => {
    process.env['DATABASE_URL'] = 'postgres://user:pw@localhost:5432/db';
    const first = getPool();
    await closePool();
    strictEqual(isPoolOpen(), false);
    ok(getPool() !== first);
  });

  it('fails a query with the configuration error when there is no database configured', async () => {
    delete process.env['DATABASE_URL'];
    await rejects(() => query('select 1'), DatabaseNotConfiguredError);
  });
});

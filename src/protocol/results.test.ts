import { strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { complete, completeList } from './results.js';

describe('results', () => {
  it('marks a result complete', () => {
    strictEqual(complete({ a: 1 })['resultType'], 'complete');
  });

  it('cannot have its resultType clobbered by the body', () => {
    strictEqual(complete({ resultType: 'input_required' })['resultType'], 'complete');
  });

  it('gives a list result the cache hints the spec requires', () => {
    const result = completeList({ tools: [] }, 60_000);
    strictEqual(result['ttlMs'], 60_000);
    strictEqual(result['cacheScope'], 'private');
    strictEqual(result['resultType'], 'complete');
  });

  it('defaults a list to private, because every answer is tied to one login', () => {
    strictEqual(completeList({ tools: [] }, 1)['cacheScope'], 'private');
  });
});
